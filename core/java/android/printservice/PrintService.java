/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.printservice;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.print.IPrinterDiscoveryObserver;
import android.print.PrintJobInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 * This is the base class for implementing print services. A print service
 * knows how to discover and interact one or more printers via one or more
 * protocols.
 * </p>
 * <h3>Printer discovery</h3>
 * <p>
 * A print service is responsible for discovering and reporting printers.
 * A printer discovery period starts with a call to
 * {@link #onStartPrinterDiscovery()} and ends with a call to
 * {@link #onStopPrinterDiscovery()}. During a printer discovery
 * period the print service reports newly discovered printers by
 * calling {@link #addDiscoveredPrinters(List)} and reports added printers
 * that disappeared by calling {@link #removeDiscoveredPrinters(List)}.
 * Calls to {@link #addDiscoveredPrinters(List)} and
 * {@link #removeDiscoveredPrinters(List)} before a call to
 * {@link #onStartPrinterDiscovery()} and after a call to
 * {@link #onStopPrinterDiscovery()} are a no-op.
 * </p>
 * <p>
 * For every printer discovery period all printers have to be added. Each
 * printer known to this print service should be added only once during a
 * discovery period, unless it was added and then removed before that.
 * Only an already added printer can be removed.
 * </p>
 * <h3>Print jobs</h3>
 * <p>
 * When a new print job targeted to the printers managed by this print
 * service is queued, i.e. ready for processing by the print service,
 * a call to {@link #onPrintJobQueued(PrintJob)} is made and the print
 * service may handle it immediately or schedule that for an appropriate
 * time in the future. The list of all print jobs for this service
 * are be available by calling {@link #getPrintJobs()}.
 * </p>
 * <p>
 * A print service is responsible for setting the print job state as
 * appropriate while processing it. Initially, a print job is in a
 * {@link PrintJobInfo#STATE_QUEUED} state which means that the data to
 * be printed is spooled by the system and the print service can obtain
 * that data by calling {@link PrintJob#getDocument()}. A queued print
 * job's {@link PrintJob#isQueued()} method returns true.
 * </p>
 * <p>
 * After the print service starts printing the data it should set the
 * print job state to {@link PrintJobInfo#STATE_STARTED} by calling
 * {@link PrintJob#start()}. Upon successful completion, the print job
 * state has to be set to {@link PrintJobInfo#STATE_COMPLETED} by calling
 * {@link PrintJob#complete()}. In case of a failure, the print job
 * state should be set to {@link PrintJobInfo#STATE_FAILED} by calling
 * {@link PrintJob#fail(CharSequence)}. If a print job is in a
 * {@link PrintJobInfo#STATE_STARTED} state, i.e. {@link PrintJob#isStarted()}
 * return true, and the user requests to cancel it, the print service will
 * receive a call to {@link #onRequestCancelPrintJob(PrintJob)} which
 * requests from the service to do a best effort in canceling the job. In
 * case the job is successfully canceled, its state has to be set to
 * {@link PrintJobInfo#STATE_CANCELED}. by calling {@link PrintJob#cancel()}.
 * </p>
 * <h3>Lifecycle</h3>
 * <p>
 * The lifecycle of a print service is managed exclusively by the system
 * and follows the established service lifecycle. Additionally, starting
 * or stopping a print service is triggered exclusively by an explicit
 * user action through enabling or disabling it in the device settings.
 * After the system binds to a print service, it calls {@link #onConnected()}.
 * This method can be overriden by clients to perform post binding setup.
 * Also after the system unbinds from a print service, it calls
 * {@link #onDisconnected()}. This method can be overriden by clients to
 * perform post unbinding cleanup.
 * </p>
 * <h3>Declaration</h3>
 * <p>
 * A print service is declared as any other service in an AndroidManifest.xml
 * but it must also specify that it handles the {@link android.content.Intent}
 * with action {@link #SERVICE_INTERFACE}. Failure to declare this intent
 * will cause the system to ignore the print service. Additionally, a print
 * service must request the {@link android.Manifest.permission#BIND_PRINT_SERVICE}
 * permission to ensure that only the system can bind to it. Failure to
 * declare this intent will cause the system to ignore the print service.
 * Following is an example declaration:
 * </p>
 * <pre>
 * &lt;service android:name=".MyPrintService"
 *         android:permission="android.permission.BIND_PRINT_SERVICE"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="android.printservice.PrintService" /&gt;
 *     &lt;/intent-filter&gt;
 *     . . .
 * &lt;/service&gt;
 * </pre>
 * <h3>Configuration</h3>
 * <p>
 * A print service can be configured by specifying an optional settings
 * activity which exposes service specific options, an optional add
 * prints activity which is used for manual addition of printers, vendor
 * name ,etc. It is a responsibility of the system to launch the settings
 * and add printers activities when appropriate.
 * </p>
 * <p>
 * A print service is configured by providing a
 * {@link #SERVICE_META_DATA meta-data} entry in the manifest when declaring
 * the service. A service declaration with a meta-data tag is presented
 * below:
 * <pre> &lt;service android:name=".MyPrintService"
 *         android:permission="android.permission.BIND_PRINT_SERVICE"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="android.printservice.PrintService" /&gt;
 *     &lt;/intent-filter&gt;
 *     &lt;meta-data android:name="android.printservice" android:resource="@xml/printservice" /&gt;
 * &lt;/service&gt;</pre>
 * </p>
 * <p>
 * For more details refer to {@link #SERVICE_META_DATA} and
 * <code>&lt;{@link android.R.styleable#PrintService print-service}&gt;</code>.
 * </p>
 */
public abstract class PrintService extends Service {

    private static final String LOG_TAG = "PrintService";

    /**
     * The {@link Intent} action that must be declared as handled by a service
     * in its manifest to allow the system to recognize it as a print service.
     */
    public static final String SERVICE_INTERFACE = "android.printservice.PrintService";

    /**
     * Name under which a PrintService component publishes additional information
     * about itself. This meta-data must reference an XML resource containing a
     * <code>&lt;{@link android.R.styleable#PrintService print-service}&gt;</code>
     * tag. This is a a sample XML file configuring a print service:
     * <pre> &lt;print-service
     *     android:vendor="SomeVendor"
     *     android:settingsActivity="foo.bar.MySettingsActivity"
     *     andorid:addPrintersActivity="foo.bar.MyAddPrintersActivity."
     *     . . .
     * /&gt;</pre>
     */
    public static final String SERVICE_META_DATA = "android.printservice";

    private final Object mLock = new Object();

    private Handler mHandler;

    private IPrintServiceClient mClient;

    private IPrinterDiscoveryObserver mDiscoveryObserver;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        mHandler = new MyHandler(base.getMainLooper());
    }

    /**
     * The system has connected to this service.
     */
    protected void onConnected() {
        /* do nothing */
    }

    /**
     * The system has disconnected from this service.
     */
    protected void onDisconnected() {
        /* do nothing */
    }

    /**
     * Callback requesting from this service to start printer discovery.
     * At the end of the printer discovery period the system will call
     * {@link #onStopPrinterDiscovery()}. Discovered printers should be
     * reported by calling {@link #addDiscoveredPrinters(List)} and reported
     * ones that disappear should be reported by calling
     * {@link #removeDiscoveredPrinters(List)}.
     *
     * @see #onStopPrinterDiscovery()
     * @see #addDiscoveredPrinters(List)
     * @see #removeDiscoveredPrinters(List)
     * @see #updateDiscoveredPrinters(List)
     */
    protected abstract void onStartPrinterDiscovery();

    /**
     * Callback requesting from this service to stop printer discovery.
     *
     * @see #onStartPrinterDiscovery()
     * @see #addDiscoveredPrinters(List)
     * @see #removeDiscoveredPrinters(List)
     * @see #updateDiscoveredPrinters(List)
     */
    protected abstract void onStopPrinterDiscovery();

    /**
     * Adds discovered printers. This method should be called during a
     * printer discovery period, i.e. after a call to
     * {@link #onStartPrinterDiscovery()} and before the corresponding
     * call to {@link #onStopPrinterDiscovery()}, otherwise it does nothing.
     * <p>
     * <strong>Note:</strong> For every printer discovery period all
     * printers have to be added. You can call this method as many times as
     * necessary during the discovery period but should not pass in already
     * added printers. If a printer is already added in the same printer
     * discovery period, it will be ignored.
     * </p>
     * <p>
     * A {@link PrinterInfo} can have all of its required attributes specified,
     * or not. Whether all attributes are specified can be verified by calling
     * {@link PrinterInfo#hasAllRequiredAttributes()}. You can add printers
     * regardless if all required attributes are specified. When the system
     * (and the user) needs to interact with a printer, you will receive a
     * call to {@link #onRequestUpdatePrinters(List)}. If you fail to update
     * a printer that was added without all required attributes via calling
     * {@link #updateDiscoveredPrinters(List)}, then this printer will be
     * ignored, i.e. considered unavailable.
     * <p>
     *
     * @param printers A list with discovered printers.
     *
     * @see #updateDiscoveredPrinters(List)
     * @see #removeDiscoveredPrinters(List)
     * @see #onStartPrinterDiscovery()
     * @see #onStopPrinterDiscovery()
     */
    public final void addDiscoveredPrinters(List<PrinterInfo> printers) {
        final IPrinterDiscoveryObserver observer;
        synchronized (mLock) {
            observer = mDiscoveryObserver;
        }
        if (observer != null) {
            try {
                observer.onPrintersAdded(printers);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error adding discovered printers", re);
            }
        }
    }

    /**
     * Removes discovered printers given their ids. This method should be called
     * during a printer discovery period, i.e. after a call to
     * {@link #onStartPrinterDiscovery()} and before the corresponding
     * call to {@link #onStopPrinterDiscovery()}, otherwise it does nothing.
     * <p>
     * For every printer discovery period all printers have to be added. You
     * should remove only printers that were added in this printer discovery
     * period by a call to {@link #addDiscoveredPrinters(List)}. You can call
     * this method as many times as necessary during the discovery period
     * but should not pass in already removed printer ids. If a printer with
     * a given id is already removed, it will be ignored.
     * </p>
     *
     * @param printerIds A list with disappeared printer ids.
     *
     * @see #addDiscoveredPrinters(List)
     * @see #updateDiscoveredPrinters(List)
     * @see #onStartPrinterDiscovery()
     * @see #onStopPrinterDiscovery()
     */
    public final void removeDiscoveredPrinters(List<PrinterId> printerIds) {
        final IPrinterDiscoveryObserver observer;
        synchronized (mLock) {
            observer = mDiscoveryObserver;
        }
        if (observer != null) {
            try {
                observer.onPrintersRemoved(printerIds);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error removing discovered printers", re);
            }
        }
    }

    /**
     * Updates discovered printers that are already added. This method should
     * be called during a printer discovery period, i.e. after a call to
     * {@link #onStartPrinterDiscovery()} and before the corresponding
     * call to {@link #onStopPrinterDiscovery()}, otherwise it does nothing.
     * <p>
     * For every printer discovery period all printers have to be added. You
     * should update only printers that were added in this printer discovery
     * period by a call to {@link #addDiscoveredPrinters(List)}. You can call
     * this method as many times as necessary during the discovery period
     * but should not try to update already removed or never added printers.
     * If a printer is already removed or never added, it will be ignored.
     * </p>
     *
     * @param printers A list with updated printers.
     *
     * @see #addDiscoveredPrinters(List)
     * @see #removeDiscoveredPrinters(List)
     * @see #onStartPrinterDiscovery()
     * @see #onStopPrinterDiscovery()
     */
    public final void updateDiscoveredPrinters(List<PrinterInfo> printers) {
        final IPrinterDiscoveryObserver observer;
        synchronized (mLock) {
            observer = mDiscoveryObserver;
        }
        if (observer != null) {
            try {
                observer.onPrintersUpdated(printers);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error updating discovered printers", re);
            }
        }
    }

    /**
     * Called when the system will start interacting with a printer
     * giving you a change to update it in case some of its capabilities
     * have changed. For example, this method will be called when the
     * user selects a printer. Hence, it updating this printer should
     * be done as quickly as possible in order to achieve maximally
     * smooth user experience.
     * <p>
     * A {@link PrinterInfo} can have all of its required attributes specified,
     * or not. Whether all attributes are specified can be verified by calling
     * {@link PrinterInfo#hasAllRequiredAttributes()}. You can add printers
     * regardless if all required attributes are specified. When the system
     * (and the user) needs to interact with a printer, you will receive a
     * call to this method. If you fail to update a printer that was added
     * without all required attributes via calling
     * {@link #updateDiscoveredPrinters(List)}, then this printer will be
     * ignored, i.e. considered unavailable.
     * </p>
     *
     * @param printerIds The printers to be updated.
     */
    protected void onRequestUpdatePrinters(List<PrinterId> printerIds) {
    }

    /**
     * Called when canceling of a print job is requested. The service
     * should do best effort to fulfill the request. After the cancellation
     * is performed, the print job should be set to a cancelled state by
     * calling {@link PrintJob#cancel()}.
     *
     * @param printJob The print job to be canceled.
     */
    protected void onRequestCancelPrintJob(PrintJob printJob) {
    }

    /**
     * Called when there is a queued print job for one of the printers
     * managed by this print service. A queued print job is ready for
     * processing by a print service which can get the data to be printed
     * by calling {@link PrintJob#getDocument()}. This service may start
     * processing the passed in print job or schedule handling of queued
     * print jobs at a convenient time. The service can get the print
     * jobs by a call to {@link #getPrintJobs()} and examine their state
     * to find the ones with state {@link PrintJobInfo#STATE_QUEUED} by
     * calling {@link PrintJob#isQueued()}.
     *
     * @param printJob The new queued print job.
     *
     * @see #getPrintJobs()
     */
    protected abstract void onPrintJobQueued(PrintJob printJob);

    /**
     * Gets the print jobs for the printers managed by this service.
     *
     * @return The print jobs.
     */
    public final List<PrintJob> getPrintJobs() {
        final IPrintServiceClient client;
        synchronized (mLock) {
            client = mClient;
        }
        if (client == null) {
            return Collections.emptyList();
        }
        try {
            List<PrintJob> printJobs = null;
            List<PrintJobInfo> printJobInfos = client.getPrintJobInfos();
            if (printJobInfos != null) {
                final int printJobInfoCount = printJobInfos.size();
                printJobs = new ArrayList<PrintJob>(printJobInfoCount);
                for (int i = 0; i < printJobInfoCount; i++) {
                    printJobs.add(new PrintJob(printJobInfos.get(i), client));
                }
            }
            if (printJobs != null) {
                return printJobs;
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error calling getPrintJobs()", re);
        }
        return Collections.emptyList();
    }

    /**
     * Generates a global printer id from a local id. The local id is unique
     * only within this print service.
     *
     * @param localId The local id.
     * @return Global printer id.
     */
    public final PrinterId generatePrinterId(String localId) {
        return new PrinterId(new ComponentName(getPackageName(),
                getClass().getName()), localId);
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return new IPrintService.Stub() {
            @Override
            public void setClient(IPrintServiceClient client) {
                mHandler.obtainMessage(MyHandler.MSG_SET_CLEINT, client).sendToTarget();
            }

            @Override
            public void onStartPrinterDiscovery(IPrinterDiscoveryObserver observer) {
                mHandler.obtainMessage(MyHandler.MSG_ON_START_PRINTER_DISCOVERY,
                        observer).sendToTarget();
            }

            @Override
            public void onStopPrinterDiscovery() {
                mHandler.sendEmptyMessage(MyHandler.MSG_ON_STOP_PRINTER_DISCOVERY);
            }

            @Override
            public void onRequestUpdatePrinters(List<PrinterId> printerIds) {
                mHandler.obtainMessage(MyHandler.MSG_ON_REQUEST_UPDATE_PRINTERS,
                        printerIds).sendToTarget();
            }

            @Override
            public void onRequestCancelPrintJob(PrintJobInfo printJobInfo) {
                mHandler.obtainMessage(MyHandler.MSG_ON_REQUEST_CANCEL_PRINTJOB,
                        printJobInfo).sendToTarget();
            }

            @Override
            public void onPrintJobQueued(PrintJobInfo printJobInfo) {
                mHandler.obtainMessage(MyHandler.MSG_ON_PRINTJOB_QUEUED,
                        printJobInfo).sendToTarget();
            }
        };
    }

    private final class MyHandler extends Handler {
        public static final int MSG_ON_START_PRINTER_DISCOVERY = 1;
        public static final int MSG_ON_STOP_PRINTER_DISCOVERY = 2;
        public static final int MSG_ON_REQUEST_CANCEL_PRINTJOB = 3;
        public static final int MSG_ON_REQUEST_UPDATE_PRINTERS = 4;
        public static final int MSG_ON_PRINTJOB_QUEUED = 5;
        public static final int MSG_SET_CLEINT = 6;

        public MyHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleMessage(Message message) {
            final int action = message.what;
            switch (action) {
                case MSG_ON_START_PRINTER_DISCOVERY: {
                    synchronized (mLock) {
                        mDiscoveryObserver = (IPrinterDiscoveryObserver) message.obj;
                    }
                    onStartPrinterDiscovery();
                } break;

                case MSG_ON_STOP_PRINTER_DISCOVERY: {
                    synchronized (mLock) {
                        mDiscoveryObserver = null;
                    }
                    onStopPrinterDiscovery();
                } break;

                case MSG_ON_REQUEST_CANCEL_PRINTJOB: {
                    PrintJobInfo printJobInfo = (PrintJobInfo) message.obj;
                    onRequestCancelPrintJob(new PrintJob(printJobInfo, mClient));
                } break;

                case MSG_ON_REQUEST_UPDATE_PRINTERS: {
                    List<PrinterId> printerIds = (List<PrinterId>) message.obj;
                    onRequestUpdatePrinters(printerIds);
                } break;

                case MSG_ON_PRINTJOB_QUEUED: {
                    PrintJobInfo printJobInfo = (PrintJobInfo) message.obj;
                    onPrintJobQueued(new PrintJob(printJobInfo, mClient));
                } break;

                case MSG_SET_CLEINT: {
                    IPrintServiceClient client = (IPrintServiceClient) message.obj;
                    synchronized (mLock) {
                        mClient = client;
                        if (client == null) {
                            mDiscoveryObserver = null;
                        }
                    }
                    if (client != null) {
                        onConnected();
                     } else {
                        onDisconnected();
                    }
                } break;

                default: {
                    throw new IllegalArgumentException("Unknown message: " + action);
                }
            }
        }
    }
}
