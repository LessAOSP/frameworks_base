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

package android.print;

import android.print.IPrinterDiscoveryObserver;
import android.print.IPrintDocumentAdapter;
import android.print.IPrintClient;
import android.print.PrinterId;
import android.print.PrintJobInfo;
import android.print.PrintAttributes;

/**
 * Interface for communication with the core print manager service.
 *
 * @hide
 */
interface IPrintManager {
    List<PrintJobInfo> getPrintJobInfos(int appId, int userId);
    PrintJobInfo getPrintJobInfo(int printJobId, int appId, int userId);
    PrintJobInfo print(String printJobName, in IPrintClient client,
            in IPrintDocumentAdapter printAdapter, in PrintAttributes attributes,
            int appId, int userId);
    void cancelPrintJob(int printJobId, int appId, int userId);
    void restartPrintJob(int printJobId, int appId, int userId);

    void createPrinterDiscoverySession(in IPrinterDiscoveryObserver observer, int userId);
    void startPrinterDiscovery(in IPrinterDiscoveryObserver observer,
            in List<PrinterId> priorityList, int userId);
    void stopPrinterDiscovery(in IPrinterDiscoveryObserver observer, int userId);
    void validatePrinters(in List<PrinterId> printerIds, int userId);
    void startPrinterStateTracking(in PrinterId printerId, int userId);
    void stopPrinterStateTracking(in PrinterId printerId, int userId);
    void destroyPrinterDiscoverySession(in IPrinterDiscoveryObserver observer,
            int userId);
}
