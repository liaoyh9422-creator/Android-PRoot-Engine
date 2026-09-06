package bin.mt.file.content;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * DocumentsProvider implementation compatible with MT Manager's internal data/obb/user_de SAF patch.
 * Exposes internal and external app storage directories via Android DocumentsProvider protocol,
 * with extensions for symlinks, unix permissions, and modification timestamps.
 */
public class MTDataFilesProvider extends DocumentsProvider {

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            "mt_extras"
    };

    private String mPackageName;
    private File mDataDir;
    private File mUserDeDataDir;
    private File mExternalDataDir;
    private File mObbDir;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        this.mPackageName = context.getPackageName();
        this.mDataDir = context.getFilesDir().getParentFile();
        String dataPath = this.mDataDir.getPath();
        if (dataPath.startsWith("/data/user/")) {
            this.mUserDeDataDir = new File("/data/user_de/" + dataPath.substring(11));
        }
        File extFilesDir = context.getExternalFilesDir(null);
        if (extFilesDir != null) {
            this.mExternalDataDir = extFilesDir.getParentFile();
        }
        this.mObbDir = context.getObbDir();
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        Context context = getContext();
        ApplicationInfo appInfo = context.getApplicationInfo();
        String appLabel = appInfo.loadLabel(context.getPackageManager()).toString();

        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, mPackageName);
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, mPackageName);
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, mPackageName);
        row.add(DocumentsContract.Root.COLUMN_FLAGS, 17); // FLAG_SUPPORTS_CREATE (1) | FLAG_SUPPORTS_IS_CHILD (16)
        row.add(DocumentsContract.Root.COLUMN_TITLE, appLabel);
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");
        row.add(DocumentsContract.Root.COLUMN_ICON, appInfo.icon);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        appendDocumentRow(cursor, documentId, null);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        String docId = parentDocumentId;
        if (docId.endsWith("/")) {
            docId = docId.substring(0, docId.length() - 1);
        }

        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File file = resolveDocIdToFile(docId, false);
        if (file == null) {
            appendDocumentRow(cursor, docId + "/data", mDataDir);
            if (mExternalDataDir != null && mExternalDataDir.exists()) {
                appendDocumentRow(cursor, docId + "/android_data", mExternalDataDir);
            }
            if (mObbDir != null && mObbDir.exists()) {
                appendDocumentRow(cursor, docId + "/android_obb", mObbDir);
            }
            if (mUserDeDataDir != null && mUserDeDataDir.exists()) {
                appendDocumentRow(cursor, docId + "/user_de_data", mUserDeDataDir);
            }
        } else {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    appendDocumentRow(cursor, docId + "/" + child.getName(), child);
                }
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        File file = resolveDocIdToFile(documentId, false);
        if (file != null) {
            int parsedMode = ParcelFileDescriptor.parseMode(mode);
            return ParcelFileDescriptor.open(file, parsedMode);
        }
        throw new FileNotFoundException(documentId + " not found");
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = resolveDocIdToFile(parentDocumentId, true);
        if (parent != null) {
            File file = new File(parent, displayName);
            int index = 2;
            while (file.exists()) {
                file = new File(parent, displayName + " (" + (index++) + ")");
            }
            boolean success;
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                success = file.mkdir();
            } else {
                try {
                    success = file.createNewFile();
                } catch (Throwable t) {
                    t.printStackTrace();
                    success = false;
                }
            }
            if (success) {
                if (parentDocumentId.endsWith("/")) {
                    return parentDocumentId + file.getName();
                } else {
                    return parentDocumentId + "/" + file.getName();
                }
            }
        }
        throw new FileNotFoundException("Failed to create document in " + parentDocumentId + " with name " + displayName);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = resolveDocIdToFile(documentId, true);
        if (file == null || !deleteRecursively(file)) {
            throw new FileNotFoundException("Failed to delete document " + documentId);
        }
    }

    @Override
    public void removeDocument(String documentId, String parentDocumentId) throws FileNotFoundException {
        deleteDocument(documentId);
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File file = resolveDocIdToFile(documentId, true);
        if (file != null) {
            File newFile = new File(file.getParentFile(), displayName);
            if (file.renameTo(newFile)) {
                int lastSlash = documentId.lastIndexOf('/', documentId.length() - 2);
                return documentId.substring(0, lastSlash) + "/" + displayName;
            }
        }
        throw new FileNotFoundException("Failed to rename document " + documentId + " to " + displayName);
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId, String targetParentDocumentId) throws FileNotFoundException {
        File srcFile = resolveDocIdToFile(sourceDocumentId, true);
        File targetParent = resolveDocIdToFile(targetParentDocumentId, true);
        if (srcFile != null && targetParent != null) {
            File destFile = new File(targetParent, srcFile.getName());
            if (!destFile.exists() && srcFile.renameTo(destFile)) {
                if (targetParentDocumentId.endsWith("/")) {
                    return targetParentDocumentId + srcFile.getName();
                } else {
                    return targetParentDocumentId + "/" + srcFile.getName();
                }
            }
        }
        throw new FileNotFoundException("Filed to move document " + sourceDocumentId + " to " + targetParentDocumentId);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = resolveDocIdToFile(documentId, true);
        if (file == null) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        }
        return getMimeType(file);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle superRes = super.call(method, arg, extras);
        if (superRes != null) {
            return superRes;
        }
        if (method == null || !method.startsWith("mt:")) {
            return null;
        }

        Bundle result = new Bundle();
        Uri uri = extras != null ? extras.getParcelable("uri") : null;
        if (uri == null) {
            result.putBoolean("result", false);
            result.putString("message", "URI is null");
            return result;
        }

        List<String> pathSegments = uri.getPathSegments();
        String docId;
        if (pathSegments.size() >= 4) {
            docId = pathSegments.get(3);
        } else if (pathSegments.size() >= 2) {
            docId = pathSegments.get(1);
        } else {
            result.putBoolean("result", false);
            result.putString("message", "Invalid URI path segments");
            return result;
        }

        switch (method) {
            case "mt:createSymlink": {
                try {
                    File file = resolveDocIdToFile(docId, false);
                    if (file == null) {
                        result.putBoolean("result", false);
                    } else {
                        String targetPath = extras.getString("path");
                        Os.symlink(targetPath, file.getPath());
                        result.putBoolean("result", true);
                    }
                } catch (ErrnoException e) {
                    result.putBoolean("result", false);
                    result.putString("message", e.getMessage());
                } catch (Throwable t) {
                    result.putBoolean("result", false);
                    result.putString("message", t.getMessage());
                }
                break;
            }
            case "mt:setPermissions": {
                try {
                    File file = resolveDocIdToFile(docId, false);
                    if (file == null) {
                        result.putBoolean("result", false);
                    } else {
                        int permissions = extras.getInt("permissions");
                        Os.chmod(file.getPath(), permissions);
                        result.putBoolean("result", true);
                    }
                } catch (ErrnoException e) {
                    result.putBoolean("result", false);
                    result.putString("message", e.getMessage());
                } catch (Throwable t) {
                    result.putBoolean("result", false);
                    result.putString("message", t.getMessage());
                }
                break;
            }
            case "mt:setLastModified": {
                try {
                    File file = resolveDocIdToFile(docId, false);
                    if (file == null) {
                        result.putBoolean("result", false);
                    } else {
                        long time = extras.getLong("time");
                        boolean success = file.setLastModified(time);
                        result.putBoolean("result", success);
                    }
                } catch (Throwable t) {
                    result.putBoolean("result", false);
                    result.putString("message", t.toString());
                }
                break;
            }
            default: {
                result.putBoolean("result", false);
                result.putString("message", "Unsupported method: ".concat(method));
                break;
            }
        }
        return result;
    }

    private File resolveDocIdToFile(String docId, boolean checkExist) throws FileNotFoundException {
        if (!docId.startsWith(mPackageName)) {
            throw new FileNotFoundException(docId + " not found");
        }
        String sub = docId.substring(mPackageName.length());
        if (sub.startsWith("/")) {
            sub = sub.substring(1);
        }
        if (sub.isEmpty()) {
            return null;
        }

        int slashIdx = sub.indexOf('/');
        String rootKey;
        String subPath;
        if (slashIdx == -1) {
            rootKey = sub;
            subPath = "";
        } else {
            rootKey = sub.substring(0, slashIdx);
            subPath = sub.substring(slashIdx + 1);
        }

        File target = null;
        if (rootKey.equalsIgnoreCase("data")) {
            target = new File(mDataDir, subPath);
        } else if (rootKey.equalsIgnoreCase("android_data")) {
            if (mExternalDataDir != null) {
                target = new File(mExternalDataDir, subPath);
            }
        } else if (rootKey.equalsIgnoreCase("android_obb")) {
            if (mObbDir != null) {
                target = new File(mObbDir, subPath);
            }
        } else if (rootKey.equalsIgnoreCase("user_de_data")) {
            if (mUserDeDataDir != null) {
                target = new File(mUserDeDataDir, subPath);
            }
        }

        if (target != null) {
            if (checkExist) {
                try {
                    Os.lstat(target.getPath());
                } catch (Exception e) {
                    throw new FileNotFoundException(docId + " not found");
                }
            }
            return target;
        }

        throw new FileNotFoundException(docId + " not found");
    }

    private void appendDocumentRow(MatrixCursor cursor, String docId, File file) {
        if (file == null) {
            try {
                file = resolveDocIdToFile(docId, false);
            } catch (FileNotFoundException ignored) {
            }
        }

        if (file == null) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, mPackageName);
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, mPackageName);
            row.add(DocumentsContract.Document.COLUMN_SIZE, 0L);
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0L);
            row.add(DocumentsContract.Document.COLUMN_FLAGS, 0);
            return;
        }

        int flags = 0;
        if (file.isDirectory()) {
            if (file.canWrite()) {
                flags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
            }
        } else {
            if (file.canWrite()) {
                flags = DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
            }
        }

        File parent = file.getParentFile();
        if (parent != null && parent.canWrite()) {
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
        }

        String displayName;
        boolean isSubItem;
        String filePath = file.getPath();

        if (filePath.equals(mDataDir.getPath())) {
            displayName = "data";
            isSubItem = false;
        } else if (mExternalDataDir != null && filePath.equals(mExternalDataDir.getPath())) {
            displayName = "android_data";
            isSubItem = false;
        } else if (mObbDir != null && filePath.equals(mObbDir.getPath())) {
            displayName = "android_obb";
            isSubItem = false;
        } else if (mUserDeDataDir != null && filePath.equals(mUserDeDataDir.getPath())) {
            displayName = "user_de_data";
            isSubItem = false;
        } else {
            displayName = file.getName();
            isSubItem = true;
        }

        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId);
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.length());
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, getMimeType(file));
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags);
        row.add("mt_path", file.getAbsolutePath());

        if (isSubItem) {
            try {
                StructStat stat = Os.lstat(file.getPath());
                StringBuilder sb = new StringBuilder();
                sb.append(stat.st_mode).append("|").append(stat.st_uid).append("|").append(stat.st_gid);
                if ((stat.st_mode & 0xF000) == 0xA000) { // OsConstants.S_ISLNK(stat.st_mode)
                    sb.append("|").append(Os.readlink(file.getPath()));
                }
                row.add("mt_extras", sb.toString());
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    private static String getMimeType(File file) {
        if (file.isDirectory()) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        }
        String name = file.getName();
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0) {
            String ext = name.substring(lastDot + 1).toLowerCase();
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            boolean isLnk = false;
            try {
                StructStat stat = Os.lstat(file.getPath());
                if ((stat.st_mode & 0xF000) == 0xA000) {
                    isLnk = true;
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }
            if (!isLnk) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (!deleteRecursively(child)) {
                            return false;
                        }
                    }
                }
            }
        }
        return file.delete();
    }
}
