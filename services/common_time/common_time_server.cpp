/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * A service that exchanges time synchronization information between
 * a master that defines a timeline and clients that follow the timeline.
 */

#include <arpa/inet.h>
#include <assert.h>
#include <fcntl.h>
#include <linux/if_ether.h>
#include <net/if.h>
#include <net/if_arp.h>
#include <netinet/ip.h>
#include <poll.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/socket.h>

#define LOG_TAG "common_time"

#include <common_time/local_clock.h>
#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <utils/Log.h>
#include <utils/Timers.h>

#include "common_clock_service.h"
#include "clock_recovery.h"
#include "common_clock.h"

namespace android {

/***** time sync protocol packets *****/

enum TimeServicePacketType {
    TIME_PACKET_WHO_IS_MASTER_REQUEST = 1,
    TIME_PACKET_WHO_IS_MASTER_RESPONSE,
    TIME_PACKET_SYNC_REQUEST,
    TIME_PACKET_SYNC_RESPONSE,
    TIME_PACKET_MASTER_ANNOUNCEMENT,
};

struct TimeServicePacketHeader {
    TimeServicePacketHeader(TimeServicePacketType type)
        : magic(htonl(kMagic)),
          packetType(htonl(type)),
          kernelTxLocalTime(0),
          kernelTxCommonTime(0),
          kernelRxLocalTime(0) { }

    TimeServicePacketType type() const {
        return static_cast<TimeServicePacketType>(ntohl(packetType));
    }

    bool checkMagic() const {
        return (ntohl(magic) == kMagic);
    }

    static const uint32_t kMagic;

    // magic number identifying the protocol
    uint32_t magic;

    // TimeServicePacketType value
    uint32_t packetType;

    // placeholders for transmit/receive timestamps that can be filled in
    // by a kernel netfilter driver
    //
    // local time (in the transmitter's domain) when this packet was sent
    int64_t kernelTxLocalTime;
    // common time when this packet was sent
    int64_t kernelTxCommonTime;
    // local time (in the receiver's domain) when this packet was received
    int64_t kernelRxLocalTime;
} __attribute__((packed));

const uint32_t TimeServicePacketHeader::kMagic = 0x54756e67;

// packet querying for a suitable master
struct WhoIsMasterRequestPacket {
    WhoIsMasterRequestPacket() : header(TIME_PACKET_WHO_IS_MASTER_REQUEST) {}

    TimeServicePacketHeader header;

    // device ID of the sender
    uint64_t senderDeviceID;

    // If this is kInvalidTimelineID, then any master can response to this
    // request.  If this is not kInvalidTimelineID, the only a master publishing
    // the given timeline ID will respond.
    uint32_t timelineID;
} __attribute__((packed));

// response to a WhoIsMaster request
struct WhoIsMasterResponsePacket {
    WhoIsMasterResponsePacket() : header(TIME_PACKET_WHO_IS_MASTER_RESPONSE) {}

    TimeServicePacketHeader header;

    // the master's device ID
    uint64_t deviceID;

    // the timeline ID being published by this master
    uint32_t timelineID;
} __attribute__((packed));

// packet sent by a client requesting correspondence between local
// and common time
struct SyncRequestPacket {
    SyncRequestPacket() : header(TIME_PACKET_SYNC_REQUEST) {}

    TimeServicePacketHeader header;

    // timeline that the client is following
    uint32_t timelineID;

    // local time when this request was transmitted
    int64_t clientTxLocalTime;
} __attribute__((packed));

// response to a sync request sent by the master
struct SyncResponsePacket {
    SyncResponsePacket() : header(TIME_PACKET_SYNC_RESPONSE) {}

    TimeServicePacketHeader header;

    // flag that is set if the recipient of the sync request is not acting
    // as a master for the requested timeline
    uint32_t nak;

    // local time when this request was transmitted by the client
    int64_t clientTxLocalTime;

    // common time when the master received the request
    int64_t masterRxCommonTime;

    // common time when the master transmitted the response
    int64_t masterTxCommonTime;
} __attribute__((packed));

// announcement of the master's presence
struct MasterAnnouncementPacket {
    MasterAnnouncementPacket() : header(TIME_PACKET_MASTER_ANNOUNCEMENT) {}

    TimeServicePacketHeader header;

    // the master's device ID
    uint64_t deviceID;

    // the timeline ID being published by this master
    uint32_t timelineID;
} __attribute__((packed));

/***** time service implementation *****/

class CommonTimeServer : public Thread {
  public:
    CommonTimeServer();
    ~CommonTimeServer();

  private:
    bool threadLoop();

    bool runStateMachine();
    bool setup();

    void assignTimelineID();
    bool assignDeviceID();

    static bool arbitrateMaster(uint64_t deviceID1, uint64_t deviceID2);

    bool handlePacket();
    bool handleWhoIsMasterRequest (const WhoIsMasterRequestPacket* request,
                                   const sockaddr_in& srcAddr);
    bool handleWhoIsMasterResponse(const WhoIsMasterResponsePacket* response,
                                   const sockaddr_in& srcAddr);
    bool handleSyncRequest        (const SyncRequestPacket* request,
                                   const sockaddr_in& srcAddr);
    bool handleSyncResponse       (const SyncResponsePacket* response,
                                   const sockaddr_in& srcAddr);
    bool handleMasterAnnouncement (const MasterAnnouncementPacket* packet,
                                   const sockaddr_in& srcAddr);

    bool handleTimeout();
    bool handleTimeoutInitial();
    bool handleTimeoutClient();
    bool handleTimeoutMaster();
    bool handleTimeoutRonin();
    bool handleTimeoutWaitForElection();

    bool sendWhoIsMasterRequest();
    bool sendSyncRequest();
    bool sendMasterAnnouncement();

    bool becomeClient(const sockaddr_in& masterAddr,
                      uint64_t masterDeviceID,
                      uint32_t timelineID);
    bool becomeMaster();
    bool becomeRonin();
    bool becomeWaitForElection();
    bool becomeInitial();

    void notifyClockSync();
    void notifyClockSyncLoss();

    ICommonClock::State mState;
    static const char* stateToString(ICommonClock::State s);
    void setState(ICommonClock::State s);

    // interval in milliseconds of the state machine's timeout
    int mTimeoutMs;

    // common clock, local clock abstraction, and clock recovery loop
    CommonClock mCommonClock;
    LocalClock mLocalClock;
    ClockRecoveryLoop mClockRecovery;

    // implementation of ICommonClock
    sp<CommonClockService> mICommonClock;

    // UDP socket for the time sync protocol
    int mSocket;

    // unique ID of this device
    uint64_t mDeviceID;

    // timestamp captured when a packet is received
    int64_t mLastPacketRxLocalTime;

    // multicast address used for master queries and announcements
    struct sockaddr_in mMulticastAddr;

    // ID of the timeline that this device is following
    uint32_t mTimelineID;

    // flag for whether the clock has been synced to a timeline
    bool mClockSynced;

    /*** status while in the Initial state ***/
    int mInitial_WhoIsMasterRequestTimeouts;
    static const int kInitial_NumWhoIsMasterRetries;
    static const int kInitial_WhoIsMasterTimeoutMs;

    /*** status while in the Client state ***/
    struct sockaddr_in mClient_MasterAddr;
    uint64_t mClient_MasterDeviceID;
    bool mClient_SyncRequestPending;
    int mClient_SyncRequestTimeouts;
    uint32_t mClient_SyncsSentToCurMaster;
    uint32_t mClient_SyncRespsRvcedFromCurMaster;
    static const int kClient_SyncRequestIntervalMs;
    static const int kClient_SyncRequestTimeoutMs;
    static const int kClient_NumSyncRequestRetries;

    /*** status while in the Master state ***/
    static const int kMaster_AnnouncementIntervalMs;

    /*** status while in the Ronin state ***/
    int mRonin_WhoIsMasterRequestTimeouts;
    static const int kRonin_NumWhoIsMasterRetries;
    static const int kRonin_WhoIsMasterTimeoutMs;

    /*** status while in the WaitForElection state ***/
    static const int kWaitForElection_TimeoutMs;

    static const char* kServiceAddr;
    static const uint16_t kServicePort;

    static const int kInfiniteTimeout;
};

// multicast IP address used by this protocol
const char* CommonTimeServer::kServiceAddr = "224.128.87.87";

// UDP port used by this protocol
const uint16_t CommonTimeServer::kServicePort = 8787;

// mTimeoutMs value representing an infinite timeout
const int CommonTimeServer::kInfiniteTimeout = -1;

/*** Initial state constants ***/

// number of WhoIsMaster attempts sent before giving up
const int CommonTimeServer::kInitial_NumWhoIsMasterRetries = 6;

// timeout used when waiting for a response to a WhoIsMaster request
const int CommonTimeServer::kInitial_WhoIsMasterTimeoutMs = 500;

/*** Client state constants ***/

// interval between sync requests sent to the master
const int CommonTimeServer::kClient_SyncRequestIntervalMs = 1000;

// timeout used when waiting for a response to a sync request
const int CommonTimeServer::kClient_SyncRequestTimeoutMs = 400;

// number of sync requests that can fail before a client assumes its master
// is dead
const int CommonTimeServer::kClient_NumSyncRequestRetries = 5;

/*** Master state constants ***/

// timeout between announcements by the master
const int CommonTimeServer::kMaster_AnnouncementIntervalMs = 10000;

/*** Ronin state constants ***/

// number of WhoIsMaster attempts sent before declaring ourselves master
const int CommonTimeServer::kRonin_NumWhoIsMasterRetries = 4;

// timeout used when waiting for a response to a WhoIsMaster request
const int CommonTimeServer::kRonin_WhoIsMasterTimeoutMs = 500;

/*** WaitForElection state constants ***/

// how long do we wait for an announcement from a master before
// trying another election?
const int CommonTimeServer::kWaitForElection_TimeoutMs = 5000;

CommonTimeServer::CommonTimeServer()
    : Thread(false)
    , mState(ICommonClock::STATE_INITIAL)
    , mTimeoutMs(kInfiniteTimeout)
    , mClockRecovery(&mLocalClock, &mCommonClock)
    , mSocket(-1)
    , mDeviceID(0)
    , mLastPacketRxLocalTime(0)
    , mTimelineID(ICommonClock::kInvalidTimelineID)
    , mClockSynced(false)
    , mInitial_WhoIsMasterRequestTimeouts(0)
    , mClient_MasterDeviceID(0)
    , mClient_SyncRequestPending(false)
    , mClient_SyncRequestTimeouts(0)
    , mClient_SyncsSentToCurMaster(0)
    , mClient_SyncRespsRvcedFromCurMaster(0)
    , mRonin_WhoIsMasterRequestTimeouts(0) {
    memset(&mMulticastAddr, 0, sizeof(mMulticastAddr));
    memset(&mClient_MasterAddr, 0, sizeof(mClient_MasterAddr));
}

CommonTimeServer::~CommonTimeServer() {
    if (mSocket != -1) {
        close(mSocket);
        mSocket = -1;
    }
}

bool CommonTimeServer::threadLoop() {
    runStateMachine();
    IPCThreadState::self()->stopProcess();
    return false;
}

bool CommonTimeServer::runStateMachine() {
    if (!mLocalClock.initCheck())
        return false;

    if (!mCommonClock.init(mLocalClock.getLocalFreq()))
        return false;

    if (!setup())
        return false;

    // Enter the initial state; this will also send the first request to
    // discover the master
    becomeInitial();

    // run the state machine
    while (true) {
        struct pollfd pfd = {mSocket, POLLIN, 0};
        nsecs_t startNs = systemTime();
        int rc = poll(&pfd, 1, mTimeoutMs);
        int elapsedMs = ns2ms(systemTime() - startNs);
        mLastPacketRxLocalTime = mLocalClock.getLocalTime();

        if (rc == -1) {
            LOGE("%s:%d poll failed", __PRETTY_FUNCTION__, __LINE__);
            return false;
        }

        if (rc == 0) {
            mTimeoutMs = kInfiniteTimeout;
            if (!handleTimeout()) {
                LOGE("handleTimeout failed");
            }
        } else {
            if (mTimeoutMs != kInfiniteTimeout) {
                mTimeoutMs = (mTimeoutMs > elapsedMs)
                           ? mTimeoutMs - elapsedMs
                           : 0;
            }

            if (pfd.revents & POLLIN) {
                if (!handlePacket()) {
                    LOGE("handlePacket failed");
                }
            }
        }
    }

    return true;
}

bool CommonTimeServer::setup() {
    int rc;

    // seed the random number generator (used to generated timeline IDs)
    srand(static_cast<unsigned int>(systemTime()));

    // open a UDP socket for the timeline serivce
    mSocket = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (mSocket == -1) {
        LOGE("%s:%d socket failed", __PRETTY_FUNCTION__, __LINE__);
        return false;
    }

    // initialize the multicast address
    memset(&mMulticastAddr, 0, sizeof(mMulticastAddr));
    mMulticastAddr.sin_family = AF_INET;
    inet_aton(kServiceAddr, &mMulticastAddr.sin_addr);
    mMulticastAddr.sin_port = htons(kServicePort);

    // bind the socket to the time service port on all interfaces
    struct sockaddr_in bindAddr;
    memset(&bindAddr, 0, sizeof(bindAddr));
    bindAddr.sin_family = AF_INET;
    bindAddr.sin_addr.s_addr = htonl(INADDR_ANY);
    bindAddr.sin_port = htons(kServicePort);
    rc = bind(mSocket, reinterpret_cast<const sockaddr *>(&bindAddr),
            sizeof(bindAddr));
    if (rc) {
        LOGE("%s:%d bind failed", __PRETTY_FUNCTION__, __LINE__);
        return false;
    }

    // add the socket to the multicast group
    struct ip_mreq mreq;
    mreq.imr_multiaddr = mMulticastAddr.sin_addr;
    mreq.imr_interface.s_addr = htonl(INADDR_ANY);
    rc = setsockopt(mSocket, IPPROTO_IP, IP_ADD_MEMBERSHIP,
                    &mreq, sizeof(mreq));
    if (rc == -1) {
        LOGE("%s:%d setsockopt failed (err = %d)",
                __PRETTY_FUNCTION__, __LINE__, errno);
        return false;
    }

    // disable loopback of multicast packets
    const int zero = 0;
    rc = setsockopt(mSocket, IPPROTO_IP, IP_MULTICAST_LOOP,
                    &zero, sizeof(zero));
    if (rc == -1) {
        LOGE("%s:%d setsockopt failed", __PRETTY_FUNCTION__, __LINE__);
        return false;
    }

    // get the device's unique ID
    if (!assignDeviceID())
        return false;

    // start the ICommonClock service
    mICommonClock = CommonClockService::instantiate(&mCommonClock, &mLocalClock);
    if (mICommonClock == NULL)
        return false;

    return true;
}

// generate a unique device ID that can be used for arbitration
bool CommonTimeServer::assignDeviceID() {
    // on the PandaBoard, derive the device ID from the MAC address of
    // the eth0 interface
    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    ifr.ifr_addr.sa_family = AF_INET;
    strlcpy(ifr.ifr_name, "eth0", IFNAMSIZ);

    int rc = ioctl(mSocket, SIOCGIFHWADDR, &ifr);
    if (rc) {
        LOGE("%s:%d ioctl failed", __PRETTY_FUNCTION__, __LINE__);
        return false;
    }

    if (ifr.ifr_addr.sa_family != ARPHRD_ETHER) {
        LOGE("%s:%d got non-Ethernet address", __PRETTY_FUNCTION__, __LINE__);
        return false;
    }

    mDeviceID = 0;
    for (int i = 0; i < ETH_ALEN; i++) {
        mDeviceID = (mDeviceID << 8) | ifr.ifr_hwaddr.sa_data[i];
    }

    return true;
}

// generate a new timeline ID
void CommonTimeServer::assignTimelineID() {
    do {
        mTimelineID = rand();
    } while (mTimelineID == ICommonClock::kInvalidTimelineID);
}

// Select a preference between the device IDs of two potential masters.
// Returns true if the first ID wins, or false if the second ID wins.
bool CommonTimeServer::arbitrateMaster(uint64_t deviceID1,
        uint64_t deviceID2) {
    return (deviceID1 > deviceID2);
}

bool CommonTimeServer::handlePacket() {
    const int kMaxPacketSize = 100;
    uint8_t buf[kMaxPacketSize];
    struct sockaddr_in srcAddr;
    socklen_t srcAddrLen = sizeof(srcAddr);

    ssize_t recvBytes = recvfrom(
            mSocket, buf, sizeof(buf), 0,
            reinterpret_cast<const sockaddr *>(&srcAddr), &srcAddrLen);

    if (recvBytes == -1) {
        LOGE("%s:%d recvfrom failed", __PRETTY_FUNCTION__, __LINE__);
        return false;
    }

    if (recvBytes < static_cast<ssize_t>(sizeof(TimeServicePacketHeader)))
        return false;

    TimeServicePacketHeader* header =
        reinterpret_cast<TimeServicePacketHeader*>(buf);

    if (!header->checkMagic())
        return false;

    bool result;

    switch (header->type()) {
        case TIME_PACKET_WHO_IS_MASTER_REQUEST: {
            if (recvBytes <
                static_cast<ssize_t>(sizeof(WhoIsMasterRequestPacket))) {
                result = false;
            } else {
                result = handleWhoIsMasterRequest(
                        reinterpret_cast<WhoIsMasterRequestPacket*>(buf),
                        srcAddr);
            }
        } break;

        case TIME_PACKET_WHO_IS_MASTER_RESPONSE: {
            if (recvBytes <
                static_cast<ssize_t>(sizeof(WhoIsMasterResponsePacket))) {
                result = false;
            } else {
                result = handleWhoIsMasterResponse(
                        reinterpret_cast<WhoIsMasterResponsePacket*>(buf),
                        srcAddr);
            }
        } break;

        case TIME_PACKET_SYNC_REQUEST: {
            if (recvBytes < static_cast<ssize_t>(sizeof(SyncRequestPacket))) {
                result = false;
            } else {
                result = handleSyncRequest(
                        reinterpret_cast<SyncRequestPacket*>(buf),
                        srcAddr);
            }
        } break;

        case TIME_PACKET_SYNC_RESPONSE: {
            if (recvBytes < static_cast<ssize_t>(sizeof(SyncResponsePacket))) {
                result = false;
            } else {
                result = handleSyncResponse(
                        reinterpret_cast<SyncResponsePacket*>(buf),
                        srcAddr);
            }
        } break;

        case TIME_PACKET_MASTER_ANNOUNCEMENT: {
            if (recvBytes <
                static_cast<ssize_t>(sizeof(MasterAnnouncementPacket))) {
                result = false;
            } else {
                result = handleMasterAnnouncement(
                        reinterpret_cast<MasterAnnouncementPacket*>(buf),
                        srcAddr);
            }
        } break;

        default: {
            LOGD("%s:%d unknown packet type", __PRETTY_FUNCTION__, __LINE__);
            result = false;
        } break;
    }

    return result;
}

bool CommonTimeServer::handleTimeout() {
    switch (mState) {
        case ICommonClock::STATE_INITIAL:
            return handleTimeoutInitial();
        case ICommonClock::STATE_CLIENT:
            return handleTimeoutClient();
        case ICommonClock::STATE_MASTER:
            return handleTimeoutMaster();
        case ICommonClock::STATE_RONIN:
            return handleTimeoutRonin();
        case ICommonClock::STATE_WAIT_FOR_ELECTION:
            return handleTimeoutWaitForElection();
    }

    return false;
}

bool CommonTimeServer::handleTimeoutInitial() {
    if (++mInitial_WhoIsMasterRequestTimeouts ==
            kInitial_NumWhoIsMasterRetries) {
        // none of our attempts to discover a master succeeded, so make
        // this device the master
        return becomeMaster();
    } else {
        // retry the WhoIsMaster request
        return sendWhoIsMasterRequest();
    }
}

bool CommonTimeServer::handleTimeoutClient() {
    if (mClient_SyncRequestPending) {
        mClient_SyncRequestPending = false;

        if (++mClient_SyncRequestTimeouts < kClient_NumSyncRequestRetries) {
            // a sync request has timed out, so retry
            return sendSyncRequest();
        } else {
            // The master has failed to respond to a sync request for too many
            // times in a row.  Assume the master is dead and start electing
            // a new master.
            return becomeRonin();
        }
    } else {
        // initiate the next sync request
        return sendSyncRequest();
    }
}

bool CommonTimeServer::handleTimeoutMaster() {
    // send another announcement from the master
    return sendMasterAnnouncement();
}

bool CommonTimeServer::handleTimeoutRonin() {
    if (++mRonin_WhoIsMasterRequestTimeouts == kRonin_NumWhoIsMasterRetries) {
        // no other master is out there, so we won the election
        return becomeMaster();
    } else {
        return sendWhoIsMasterRequest();
    }
}

bool CommonTimeServer::handleTimeoutWaitForElection() {
    return becomeRonin();
}

bool CommonTimeServer::handleWhoIsMasterRequest(
        const WhoIsMasterRequestPacket* request,
        const sockaddr_in& srcAddr) {
    if (mState == ICommonClock::STATE_MASTER) {
        // is this request related to this master's timeline?
        if (ntohl(request->timelineID) != ICommonClock::kInvalidTimelineID &&
            ntohl(request->timelineID) != mTimelineID)
            return true;

        WhoIsMasterResponsePacket response;
        response.deviceID = htonq(mDeviceID);
        response.timelineID = htonl(mTimelineID);

        ssize_t sendBytes = sendto(
                mSocket, &response, sizeof(response), 0,
                reinterpret_cast<const sockaddr *>(&srcAddr),
                sizeof(srcAddr));
        if (sendBytes == -1) {
            LOGE("%s:%d sendto failed", __PRETTY_FUNCTION__, __LINE__);
            return false;
        }
    } else if (mState == ICommonClock::STATE_RONIN) {
        // if we hear a WhoIsMaster request from another device following
        // the same timeline and that device wins arbitration, then we will stop
        // trying to elect ourselves master and will instead wait for an
        // announcement from the election winner
        if (ntohl(request->timelineID) != mTimelineID)
            return true;

        if (arbitrateMaster(ntohq(request->senderDeviceID), mDeviceID))
            return becomeWaitForElection();

        return true;
    } else if (mState == ICommonClock::STATE_INITIAL) {
        // If a group of devices booted simultaneously (e.g. after a power
        // outage) and all of them are in the initial state and there is no
        // master, then each device may time out and declare itself master at
        // the same time.  To avoid this, listen for
        // WhoIsMaster(InvalidTimeline) requests from peers.  If we would lose
        // arbitration against that peer, reset our timeout count so that the
        // peer has a chance to become master before we time out.
        if (ntohl(request->timelineID) == ICommonClock::kInvalidTimelineID &&
                arbitrateMaster(ntohq(request->senderDeviceID), mDeviceID)) {
            mInitial_WhoIsMasterRequestTimeouts = 0;
        }
    }

    return true;
}

bool CommonTimeServer::handleWhoIsMasterResponse(
        const WhoIsMasterResponsePacket* response,
        const sockaddr_in& srcAddr) {
    if (mState == ICommonClock::STATE_INITIAL || mState == ICommonClock::STATE_RONIN) {
        return becomeClient(srcAddr,
                            ntohq(response->deviceID),
                            ntohl(response->timelineID));
    } else if (mState == ICommonClock::STATE_CLIENT) {
        // if we get multiple responses because there are multiple devices
        // who believe that they are master, then follow the master that
        // wins arbitration
        if (arbitrateMaster(ntohq(response->deviceID),
                            mClient_MasterDeviceID)) {
            return becomeClient(srcAddr,
                                ntohq(response->deviceID),
                                ntohl(response->timelineID));
        }
    }

    return true;
}

bool CommonTimeServer::handleSyncRequest(const SyncRequestPacket* request,
                                       const sockaddr_in& srcAddr) {
    SyncResponsePacket response;
    if (mState == ICommonClock::STATE_MASTER && ntohl(request->timelineID) == mTimelineID) {
        int64_t rxLocalTime = (request->header.kernelRxLocalTime) ?
            ntohq(request->header.kernelRxLocalTime) : mLastPacketRxLocalTime;

        int64_t rxCommonTime;
        if (OK != mCommonClock.localToCommon(rxLocalTime, &rxCommonTime)) {
            return false;
        }

        // TODO(johngro) : now that common time has moved out of the kernel, in
        // order to turn netfilter based timestamping of transmit and receive
        // times, we will need to make some changes to the sync request/resposne
        // packet structure.  Currently masters send back to clients RX and TX
        // times expressed in common time (since the master's local time is not
        // useful to the client).  Now that the netfilter driver has no access
        // to common time, then netfilter driver should capture the master's rx
        // local time as the packet comes in, and put the master's tx local time
        // into the packet as the response goes out.  The user mode code (this
        // function) needs to add the master's local->common transformation to
        // the packet so that the client can make use of the data.
        int64_t txLocalTime = mLocalClock.getLocalTime();;
        int64_t txCommonTime;
        if (OK != mCommonClock.localToCommon(txLocalTime, &txCommonTime)) {
            return false;
        }

        response.nak = htonl(0);
        response.clientTxLocalTime = (request->header.kernelTxLocalTime) ?
            request->header.kernelTxLocalTime : request->clientTxLocalTime;
        response.masterRxCommonTime = htonq(rxCommonTime);
        response.masterTxCommonTime = htonq(txCommonTime);
    } else {
        response.nak = htonl(1);
        response.clientTxLocalTime = htonl(0);
        response.masterRxCommonTime = htonl(0);
        response.masterTxCommonTime = htonl(0);
    }

    ssize_t sendBytes = sendto(
            mSocket, &response, sizeof(response), 0,
            reinterpret_cast<const sockaddr *>(&srcAddr),
            sizeof(srcAddr));
    if (sendBytes == -1) {
        LOGE("%s:%d sendto failed", __PRETTY_FUNCTION__, __LINE__);
        return false;
    }

    return true;
}

bool CommonTimeServer::handleSyncResponse(
        const SyncResponsePacket* response,
        const sockaddr_in& srcAddr) {
    if (mState != ICommonClock::STATE_CLIENT)
        return true;

    if ((srcAddr.sin_addr.s_addr != mClient_MasterAddr.sin_addr.s_addr) ||
        (srcAddr.sin_port        != mClient_MasterAddr.sin_port)) {
        uint32_t srcIP      = ntohl(srcAddr.sin_addr.s_addr);
        uint32_t expectedIP = ntohl(mClient_MasterAddr.sin_addr.s_addr);
        LOGI("Dropping sync response from unexpected address."
             " Expected %u.%u.%u.%u:%hu"
             " Got %u.%u.%u.%u:%hu",
             ((expectedIP >> 24) & 0xFF), ((expectedIP >> 16) & 0xFF),
             ((expectedIP >>  8) & 0xFF),  (expectedIP & 0xFF),
             ntohs(mClient_MasterAddr.sin_port),
             ((srcIP >> 24) & 0xFF), ((srcIP >> 16) & 0xFF),
             ((srcIP >>  8) & 0xFF),  (srcIP & 0xFF),
             ntohs(srcAddr.sin_port));
        return true;
    }

    if (ntohl(response->nak)) {
        // if our master is no longer accepting requests, then we need to find
        // a new master
        return becomeRonin();
    }

    mClient_SyncRequestPending = 0;
    mClient_SyncRequestTimeouts = 0;

    bool result;

    if (!(mClient_SyncRespsRvcedFromCurMaster++)) {
        // the first request/response exchange between a client and a master
        // may take unusually long due to ARP, so discard it.
        result = true;
    } else {
        int64_t clientTxLocalTime = ntohq(response->clientTxLocalTime);
        int64_t clientRxLocalTime = (response->header.kernelRxLocalTime)
                                  ? ntohq(response->header.kernelRxLocalTime)
                                  : mLastPacketRxLocalTime;
        int64_t masterTxCommonTime = (response->header.kernelTxCommonTime)
                                   ?  ntohq(response->header.kernelTxCommonTime)
                                   : ntohq(response->masterTxCommonTime);
        int64_t masterRxCommonTime = ntohq(response->masterRxCommonTime);

        int64_t rtt       = (clientRxLocalTime - clientTxLocalTime);
        int64_t avgLocal  = (clientTxLocalTime + clientRxLocalTime) >> 1;
        int64_t avgCommon = (masterTxCommonTime + masterRxCommonTime) >> 1;
        result = mClockRecovery.pushDisciplineEvent(avgLocal, avgCommon, rtt);

        if (result) {
            // indicate to listeners that we've synced to the common timeline
            notifyClockSync();
        } else {
            LOGE("Panic!  Observed clock sync error is too high to tolerate,"
                    " resetting state machine and starting over.");
            notifyClockSyncLoss();
            return becomeInitial();
        }
    }

    mTimeoutMs = kClient_SyncRequestIntervalMs;
    return result;
}

bool CommonTimeServer::handleMasterAnnouncement(
        const MasterAnnouncementPacket* packet,
        const sockaddr_in& srcAddr) {
    uint64_t newDeviceID = ntohq(packet->deviceID);
    uint32_t newTimelineID = ntohl(packet->timelineID);

    if (mState == ICommonClock::STATE_INITIAL ||
        mState == ICommonClock::STATE_RONIN ||
        mState == ICommonClock::STATE_WAIT_FOR_ELECTION) {
        // if we aren't currently following a master, then start following
        // this new master
        return becomeClient(srcAddr, newDeviceID, newTimelineID);
    } else if (mState == ICommonClock::STATE_CLIENT) {
        // if the new master wins arbitration against our current master,
        // then become a client of the new master
        if (arbitrateMaster(newDeviceID, mClient_MasterDeviceID))
            return becomeClient(srcAddr, newDeviceID, newTimelineID);
    } else if (mState == ICommonClock::STATE_MASTER) {
        // two masters are competing - if the new one wins arbitration, then
        // cease acting as master
        if (arbitrateMaster(newDeviceID, mDeviceID))
            return becomeClient(srcAddr, newDeviceID, newTimelineID);
    }

    return true;
}

bool CommonTimeServer::sendWhoIsMasterRequest() {
    assert(mState == ICommonClock::STATE_INITIAL || mState == ICommonClock::STATE_RONIN);

    WhoIsMasterRequestPacket request;
    request.senderDeviceID = htonq(mDeviceID);
    request.timelineID = htonl(mTimelineID);

    ssize_t sendBytes = sendto(
            mSocket, &request, sizeof(request), 0,
            reinterpret_cast<const sockaddr *>(&mMulticastAddr),
            sizeof(mMulticastAddr));
    if (sendBytes == -1) {
        LOGE("%s:%d sendto failed", __PRETTY_FUNCTION__, __LINE__);
    }

    if (mState == ICommonClock::STATE_INITIAL) {
        mTimeoutMs = kInitial_WhoIsMasterTimeoutMs;
    } else {
        mTimeoutMs = kRonin_WhoIsMasterTimeoutMs;
    }

    return (sendBytes != -1);
}

bool CommonTimeServer::sendSyncRequest() {
    assert(mState == ICommonClock::STATE_CLIENT);

    SyncRequestPacket request;
    request.timelineID = htonl(mTimelineID);
    request.clientTxLocalTime = htonq(mLocalClock.getLocalTime());

    ssize_t sendBytes = sendto(
            mSocket, &request, sizeof(request), 0,
            reinterpret_cast<const sockaddr *>(&mClient_MasterAddr),
            sizeof(mClient_MasterAddr));
    if (sendBytes == -1) {
        LOGE("%s:%d sendto failed", __PRETTY_FUNCTION__, __LINE__);
    }

    mClient_SyncsSentToCurMaster++;
    mTimeoutMs = kClient_SyncRequestTimeoutMs;
    mClient_SyncRequestPending = true;
    return (sendBytes != -1);
}

bool CommonTimeServer::sendMasterAnnouncement() {
    assert(mState == ICommonClock::STATE_MASTER);

    MasterAnnouncementPacket announce;
    announce.deviceID = htonq(mDeviceID);
    announce.timelineID = htonl(mTimelineID);

    ssize_t sendBytes = sendto(
            mSocket, &announce, sizeof(announce), 0,
            reinterpret_cast<const sockaddr *>(&mMulticastAddr),
            sizeof(mMulticastAddr));
    if (sendBytes == -1) {
        LOGE("%s:%d sendto failed", __PRETTY_FUNCTION__, __LINE__);
    }

    mTimeoutMs = kMaster_AnnouncementIntervalMs;
    return (sendBytes != -1);
}

bool CommonTimeServer::becomeClient(const sockaddr_in& masterAddr,
                                  uint64_t masterDeviceID,
                                  uint32_t timelineID) {
    uint32_t newIP = ntohl(masterAddr.sin_addr.s_addr);
    uint32_t oldIP = ntohl(mClient_MasterAddr.sin_addr.s_addr);
    LOGI("%s --> CLIENT%s"
         " OldMaster: %016llx::%08x::%u.%u.%u.%u:%hu"
         " NewMaster: %016llx::%08x::%u.%u.%u.%u:%hu",
         stateToString(mState),
         (mTimelineID != timelineID) ? " (new timeline)" : "",
         mClient_MasterDeviceID, mTimelineID,
         ((oldIP >> 24) & 0xFF), ((oldIP >> 16) & 0xFF),
         ((oldIP >>  8) & 0xFF),  (oldIP & 0xFF),
         ntohs(mClient_MasterAddr.sin_port),
         masterDeviceID, timelineID,
         ((newIP >> 24) & 0xFF), ((newIP >> 16) & 0xFF),
         ((newIP >>  8) & 0xFF),  (newIP & 0xFF),
         ntohs(masterAddr.sin_port));

    if (mTimelineID != timelineID) {
        // start following a new timeline
        mTimelineID = timelineID;
        mClockRecovery.reset(true, true);
        notifyClockSyncLoss();
    } else {
        // start following a new master on the existing timeline
        mClockRecovery.reset(false, true);
    }

    mClient_MasterAddr = masterAddr;
    mClient_MasterDeviceID = masterDeviceID;
    mClient_SyncRequestPending = 0;
    mClient_SyncRequestTimeouts = 0;
    mClient_SyncsSentToCurMaster = 0;
    mClient_SyncRespsRvcedFromCurMaster = 0;

    setState(ICommonClock::STATE_CLIENT);

    // add some jitter to when the various clients send their requests
    // in order to reduce the likelihood that a group of clients overload
    // the master after receiving a master announcement
    usleep((rand() % 100) * 1000);

    return sendSyncRequest();
}

bool CommonTimeServer::becomeMaster() {
    uint32_t oldTimelineID = mTimelineID;
    if (mTimelineID == ICommonClock::kInvalidTimelineID) {
        // this device has not been following any existing timeline,
        // so it will create a new timeline and declare itself master
        assert(!mCommonClock.isValid());

        // set the common time basis
        mCommonClock.setBasis(mLocalClock.getLocalTime(), 0);

        // assign an arbitrary timeline iD
        assignTimelineID();

        // notify listeners that we've created a common timeline
        notifyClockSync();
    }

    LOGI("%s --> MASTER %s timeline %08x",
         stateToString(mState),
         (oldTimelineID == mTimelineID) ? "taking ownership of"
                                        : "creating new",
         mTimelineID);

    mClockRecovery.reset(false, true);

    setState(ICommonClock::STATE_MASTER);
    return sendMasterAnnouncement();
}

bool CommonTimeServer::becomeRonin() {
    // If we were the client of a given timeline, but had never received even a
    // single time sync packet, then we transition back to Initial instead of
    // Ronin.  If we transition to Ronin and end up becoming the new Master, we
    // will be unable to service requests for other clients because we never
    // actually knew what time it was.  By going to initial, we ensure that
    // other clients who know what time it is, but would lose master arbitration
    // in the Ronin case, will step up and become the proper new master of the
    // old timeline.
    uint32_t oldIP = ntohl(mClient_MasterAddr.sin_addr.s_addr);
    if (mCommonClock.isValid()) {
        LOGI("%s --> RONIN : lost track of previously valid timeline "
             "%016llx::%08x::%u.%u.%u.%u:%hu (%d TXed %d RXed)",
             stateToString(mState),
             mClient_MasterDeviceID, mTimelineID,
             ((oldIP >> 24) & 0xFF), ((oldIP >> 16) & 0xFF),
             ((oldIP >>  8) & 0xFF),  (oldIP & 0xFF),
             ntohs(mClient_MasterAddr.sin_port),
             mClient_SyncsSentToCurMaster,
             mClient_SyncRespsRvcedFromCurMaster);

        mRonin_WhoIsMasterRequestTimeouts = 0;
        setState(ICommonClock::STATE_RONIN);
        return sendWhoIsMasterRequest();
    } else {
        LOGI("%s --> INITIAL : never synced timeline "
             "%016llx::%08x::%u.%u.%u.%u:%hu (%d TXed %d RXed)",
             stateToString(mState),
             mClient_MasterDeviceID, mTimelineID,
             ((oldIP >> 24) & 0xFF), ((oldIP >> 16) & 0xFF),
             ((oldIP >>  8) & 0xFF),  (oldIP & 0xFF),
             ntohs(mClient_MasterAddr.sin_port),
             mClient_SyncsSentToCurMaster,
             mClient_SyncRespsRvcedFromCurMaster);

        return becomeInitial();
    }
}

bool CommonTimeServer::becomeWaitForElection() {
    LOGI("%s --> WAIT_FOR_ELECTION : dropping out of election, waiting %d mSec"
         " for completion.", stateToString(mState), kWaitForElection_TimeoutMs);

    setState(ICommonClock::STATE_WAIT_FOR_ELECTION);
    mTimeoutMs = kWaitForElection_TimeoutMs;
    return true;
}

bool CommonTimeServer::becomeInitial() {
    LOGI("Entering INITIAL, total reset.");

    setState(ICommonClock::STATE_INITIAL);

    // reset clock recovery
    mClockRecovery.reset(true, true);

    // reset internal state bookkeeping.
    mTimeoutMs = kInfiniteTimeout;
    mLastPacketRxLocalTime = 0;
    mTimelineID = ICommonClock::kInvalidTimelineID;
    mClockSynced = false;
    mInitial_WhoIsMasterRequestTimeouts = 0;
    mClient_MasterDeviceID = 0;
    mClient_SyncsSentToCurMaster = 0;
    mClient_SyncRespsRvcedFromCurMaster = 0;
    mClient_SyncRequestPending = false;
    mClient_SyncRequestTimeouts = 0;
    mRonin_WhoIsMasterRequestTimeouts = 0;

    // send the first request to discover the master
    return sendWhoIsMasterRequest();
}

void CommonTimeServer::notifyClockSync() {
    if (!mClockSynced) {
        mICommonClock->notifyOnClockSync(mTimelineID);
        mClockSynced = true;
    }
}

void CommonTimeServer::notifyClockSyncLoss() {
    if (mClockSynced) {
        mICommonClock->notifyOnClockSyncLoss();
        mClockSynced = false;
    }
}

void CommonTimeServer::setState(ICommonClock::State s) {
    mState = s;
}

const char* CommonTimeServer::stateToString(ICommonClock::State s) {
    switch(s) {
        case ICommonClock::STATE_INITIAL:
            return "INITIAL";
        case ICommonClock::STATE_CLIENT:
            return "CLIENT";
        case ICommonClock::STATE_MASTER:
            return "MASTER";
        case ICommonClock::STATE_RONIN:
            return "RONIN";
        case ICommonClock::STATE_WAIT_FOR_ELECTION:
            return "WAIT_FOR_ELECTION";
        default:
            return "unknown";
    }
}

}  // namespace android

int main(int argc, char *argv[]) {
    using namespace android;

    sp<CommonTimeServer> service = new CommonTimeServer();
    if (service == NULL)
        return 1;

    ProcessState::self()->startThreadPool();
    service->run("CommonTimeServer", ANDROID_PRIORITY_NORMAL);

    IPCThreadState::self()->joinThreadPool();
    return 0;
}
