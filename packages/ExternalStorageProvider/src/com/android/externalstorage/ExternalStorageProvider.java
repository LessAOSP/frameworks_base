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

package com.android.externalstorage;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.graphics.Point;
import android.media.ExifInterface;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class ExternalStorageProvider extends DocumentsProvider {
    private static final String TAG = "ExternalStorage";

    // docId format: root:path/to/file

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_ROOT_TYPE, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
            Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private static class RootInfo {
        public String rootId;
        public int rootType;
        public int flags;
        public int icon;
        public String title;
        public String docId;
    }

    private ArrayList<RootInfo> mRoots;
    private HashMap<String, RootInfo> mIdToRoot;
    private HashMap<String, File> mIdToPath;

    @Override
    public boolean onCreate() {
        mRoots = Lists.newArrayList();
        mIdToRoot = Maps.newHashMap();
        mIdToPath = Maps.newHashMap();

        // TODO: support multiple storage devices

        try {
            final String rootId = "primary";
            final File path = Environment.getExternalStorageDirectory();
            mIdToPath.put(rootId, path);

            final RootInfo root = new RootInfo();
            root.rootId = "primary";
            root.rootType = Root.ROOT_TYPE_DEVICE;
            root.flags = Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY | Root.FLAG_ADVANCED;
            root.icon = R.drawable.ic_pdf;
            root.title = getContext().getString(R.string.root_internal_storage);
            root.docId = getDocIdForFile(path);
            mRoots.add(root);
            mIdToRoot.put(rootId, root);
        } catch (FileNotFoundException e) {
            throw new IllegalStateException(e);
        }

        return true;
    }

    private static String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    private String getDocIdForFile(File file) throws FileNotFoundException {
        String path = file.getAbsolutePath();

        // Find the most-specific root path
        Map.Entry<String, File> mostSpecific = null;
        for (Map.Entry<String, File> root : mIdToPath.entrySet()) {
            final String rootPath = root.getValue().getPath();
            if (path.startsWith(rootPath) && (mostSpecific == null
                    || rootPath.length() > mostSpecific.getValue().getPath().length())) {
                mostSpecific = root;
            }
        }

        if (mostSpecific == null) {
            throw new FileNotFoundException("Failed to find root that contains " + path);
        }

        // Start at first char of path under root
        final String rootPath = mostSpecific.getValue().getPath();
        if (rootPath.equals(path)) {
            path = "";
        } else if (rootPath.endsWith("/")) {
            path = path.substring(rootPath.length());
        } else {
            path = path.substring(rootPath.length() + 1);
        }

        return mostSpecific.getKey() + ':' + path;
    }

    private File getFileForDocId(String docId) throws FileNotFoundException {
        final int splitIndex = docId.indexOf(':', 1);
        final String tag = docId.substring(0, splitIndex);
        final String path = docId.substring(splitIndex + 1);

        File target = mIdToPath.get(tag);
        if (target == null) {
            throw new FileNotFoundException("No root for " + tag);
        }
        target = new File(target, path);
        if (!target.exists()) {
            throw new FileNotFoundException("Missing file for " + docId + " at " + target);
        }
        return target;
    }

    private void includeFile(MatrixCursor result, String docId, File file)
            throws FileNotFoundException {
        if (docId == null) {
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
        }

        int flags = 0;

        if (file.isDirectory()) {
            flags |= Document.FLAG_DIR_SUPPORTS_SEARCH;
        }
        if (file.isDirectory() && file.canWrite()) {
            flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        }
        if (file.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
            flags |= Document.FLAG_SUPPORTS_DELETE;
        }

        final String displayName = file.getName();
        final String mimeType = getTypeForFile(file);
        if (mimeType.startsWith("image/")) {
            flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
        }

        final RowBuilder row = result.newRow();
        row.offer(Document.COLUMN_DOCUMENT_ID, docId);
        row.offer(Document.COLUMN_DISPLAY_NAME, displayName);
        row.offer(Document.COLUMN_SIZE, file.length());
        row.offer(Document.COLUMN_MIME_TYPE, mimeType);
        row.offer(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.offer(Document.COLUMN_FLAGS, flags);
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        for (String rootId : mIdToPath.keySet()) {
            final RootInfo root = mIdToRoot.get(rootId);
            final File path = mIdToPath.get(rootId);

            final RowBuilder row = result.newRow();
            row.offer(Root.COLUMN_ROOT_ID, root.rootId);
            row.offer(Root.COLUMN_ROOT_TYPE, root.rootType);
            row.offer(Root.COLUMN_FLAGS, root.flags);
            row.offer(Root.COLUMN_ICON, root.icon);
            row.offer(Root.COLUMN_TITLE, root.title);
            row.offer(Root.COLUMN_DOCUMENT_ID, root.docId);
            row.offer(Root.COLUMN_AVAILABLE_BYTES, path.getFreeSpace());
        }
        return result;
    }

    @Override
    public String createDocument(String docId, String mimeType, String displayName)
            throws FileNotFoundException {
        final File parent = getFileForDocId(docId);
        displayName = validateDisplayName(mimeType, displayName);

        final File file = new File(parent, displayName);
        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            if (!file.mkdir()) {
                throw new IllegalStateException("Failed to mkdir " + file);
            }
        } else {
            try {
                if (!file.createNewFile()) {
                    throw new IllegalStateException("Failed to touch " + file);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to touch " + file + ": " + e);
            }
        }
        return getDocIdForFile(file);
    }

    @Override
    public void deleteDocument(String docId) throws FileNotFoundException {
        final File file = getFileForDocId(docId);
        if (!file.delete()) {
            throw new IllegalStateException("Failed to delete " + file);
        }
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(
            String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        final File parent = getFileForDocId(parentDocumentId);
        for (File file : parent.listFiles()) {
            includeFile(result, null, file);
        }
        return result;
    }

    @Override
    public Cursor querySearchDocuments(String parentDocumentId, String query, String[] projection)
            throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        final File parent = getFileForDocId(parentDocumentId);

        final LinkedList<File> pending = new LinkedList<File>();
        pending.add(parent);
        while (!pending.isEmpty() && result.getCount() < 20) {
            final File file = pending.removeFirst();
            if (file.isDirectory()) {
                for (File child : file.listFiles()) {
                    pending.add(child);
                }
            } else {
                if (file.getName().toLowerCase().contains(query)) {
                    includeFile(result, null, file);
                }
            }
        }
        return result;
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        return getTypeForFile(file);
    }

    @Override
    public ParcelFileDescriptor openDocument(
            String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        return ParcelFileDescriptor.open(file, ContentResolver.modeToMode(null, mode));
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(
            String documentId, Point sizeHint, CancellationSignal signal)
            throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                file, ParcelFileDescriptor.MODE_READ_ONLY);

        try {
            final ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            final long[] thumb = exif.getThumbnailRange();
            if (thumb != null) {
                return new AssetFileDescriptor(pfd, thumb[0], thumb[1]);
            }
        } catch (IOException e) {
        }

        return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    private static String getTypeForFile(File file) {
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        } else {
            return getTypeForName(file.getName());
        }
    }

    private static String getTypeForName(String name) {
        final int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = name.substring(lastDot + 1);
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) {
                return mime;
            }
        }

        return "application/octet-stream";
    }

    private static String validateDisplayName(String mimeType, String displayName) {
        if (Document.MIME_TYPE_DIR.equals(mimeType)) {
            return displayName;
        } else {
            // Try appending meaningful extension if needed
            if (!mimeType.equals(getTypeForName(displayName))) {
                final String extension = MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(mimeType);
                if (extension != null) {
                    displayName += "." + extension;
                }
            }

            return displayName;
        }
    }
}
