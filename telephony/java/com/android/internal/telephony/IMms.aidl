/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.telephony;

import android.app.PendingIntent;

/**
 * Service interface to handle MMS API requests
 */
interface IMms {
    /**
     * Send an MMS message
     *
     * @param callingPkg the package name of the calling app
     * @param pdu the MMS message encoded in standard MMS PDU format
     * @param locationUrl the optional location url for where this message should be sent to
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed
     */
    void sendMessage(String callingPkg, in byte[] pdu, String locationUrl,
            in PendingIntent sentIntent);

    /**
     * Download an MMS message using known location and transaction id
     *
     * @param callingPkg the package name of the calling app
     * @param locationUrl the location URL of the MMS message to be downloaded, usually obtained
     *  from the MMS WAP push notification
     * @param downloadedIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is downloaded, or the download is failed
     */
    void downloadMessage(String callingPkg, String locationUrl, in PendingIntent downloadedIntent);

    /**
     * Update the status of a pending (send-by-IP) MMS message handled by the carrier app.
     * If the carrier app fails to send this message, it would be resent via carrier network.
     *
     * @param messageRef the reference number of the MMS message.
     * @param success True if and only if the message was sent successfully. If its value is
     *  false, this message should be resent via carrier network
     */
    void updateMmsSendStatus(int messageRef, boolean success);

    /**
     * Update the status of a pending (download-by-IP) MMS message handled by the carrier app.
     * If the carrier app fails to download this message, it would be re-downloaded via carrier
     * network.
     *
     * @param messageRef the reference number of the MMS message.
     * @param pdu non-empty if downloaded successfully, otherwise, it is empty and the message
     *  will be downloaded via carrier network
     */
    void updateMmsDownloadStatus(int messageRef, in byte[] pdu);
}
