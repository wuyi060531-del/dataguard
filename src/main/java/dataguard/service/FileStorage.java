package dataguard.service;

import dataguard.persistence.StoredDataset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** Immutable managed copies prevent subsequent edits to raw imports from changing history. */
@Component
public class FileStorage {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private final Path storageRoot;
    private final Path importRoot;

    public FileStorage(@Value("${dataguard.storage-root:./var/uploads}") String storageRoot,
                       @Value("${dataguard.import-root:./data/raw}") String importRoot) {
        try {
            this.storageRoot = Files.createDirectories(Path.of(storageRoot).toAbsolutePath().normalize()).toRealPath();
            this.importRoot = Path.of(importRoot).toAbsolutePath().normalize();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot initialize DataGuard managed storage", failure);
        }
    }

    ManagedFile store(String fileName, InputStream content) {
        validateFileName(fileName);
        if (content == null) {
            throw new ServiceException("INVALID_FILE", 400, "请选择 CSV 文件。");
        }
        Path temporary = null;
        Path destination = null;
        try {
            temporary = privateTemp("upload-", ".part");
            Fingerprint fingerprint = copy(content, temporary);
            String storageName = UUID.randomUUID() + ".csv";
            destination = storageRoot.resolve(storageName);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination);
            }
            return new ManagedFile(storageName, destination, fingerprint.size(), fingerprint.sha256());
        } catch (ServiceException failure) {
            deleteOwned(temporary);
            deleteOwned(destination);
            throw failure;
        } catch (IOException failure) {
            deleteOwned(temporary);
            deleteOwned(destination);
            throw new ServiceException("FILE_STORAGE_ERROR", 503, "无法保存 CSV 文件。", failure);
        }
    }

    ManagedFile importFile(String relativePath) {
        Path input = resolveImport(relativePath);
        try (InputStream stream = Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS)) {
            return store(input.getFileName().toString(), stream);
        } catch (IOException failure) {
            throw new ServiceException("FILE_READ_ERROR", 400, "无法读取指定的 CSV 文件。", failure);
        }
    }

    String importFileName(String relativePath) {
        return resolveImport(relativePath).getFileName().toString();
    }

    /** Inspect a verified temporary snapshot, rather than reopening an unchecked stored path. */
    VerifiedFile verifiedSnapshot(StoredDataset dataset) {
        Path temporary = null;
        try {
            String name = dataset.storageName();
            if (name == null || !name.matches("[0-9a-f-]{36}\\.csv")) {
                throw new ServiceException("FILE_INTEGRITY_ERROR", 409, "托管数据文件的元数据无效。");
            }
            Path input = storageRoot.resolve(name).normalize();
            if (!input.startsWith(storageRoot) || Files.isSymbolicLink(input)
                    || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)
                    || !input.toRealPath().startsWith(storageRoot)) {
                throw new ServiceException("FILE_INTEGRITY_ERROR", 409, "托管数据文件丢失或不是有效文件。");
            }
            if (Files.size(input) != dataset.info().sizeBytes()) {
                throw new ServiceException("FILE_INTEGRITY_ERROR", 409, "托管数据文件大小已发生变化。");
            }
            temporary = privateTemp("inspection-", ".csv");
            Fingerprint actual;
            try (InputStream stream = Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS)) {
                actual = copy(stream, temporary);
            }
            if (actual.size() != dataset.info().sizeBytes() || !actual.sha256().equals(dataset.info().sha256())) {
                throw new ServiceException("FILE_INTEGRITY_ERROR", 409, "托管数据文件校验失败，请重新上传。");
            }
            return new VerifiedFile(temporary);
        } catch (ServiceException failure) {
            deleteOwned(temporary);
            if ("FILE_TOO_LARGE".equals(failure.getCode())) {
                throw new ServiceException("FILE_INTEGRITY_ERROR", 409, "托管数据文件大小已发生变化。", failure);
            }
            throw failure;
        } catch (IOException failure) {
            deleteOwned(temporary);
            throw new ServiceException("FILE_INTEGRITY_ERROR", 409, "托管数据文件无法安全读取。", failure);
        }
    }

    void remove(ManagedFile file) {
        deleteOwned(file.path());
    }

    static void validateFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.length() > 255
                || fileName.contains("/") || fileName.contains("\\")
                || fileName.codePoints().anyMatch(Character::isISOControl)
                || !fileName.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new ServiceException("INVALID_FILE_NAME", 400, "文件名必须是无路径、无控制字符的 .csv 名称（最多 255 字符）。");
        }
    }

    private Path resolveImport(String value) {
        if (value == null || value.isBlank() || value.contains("/") || value.contains("\\")
                || value.equals(".") || value.equals("..") || value.matches("^[A-Za-z]:.*")
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new ServiceException("INVALID_IMPORT_PATH", 400, "只允许导入目录直接包含的 CSV 文件名，不允许子目录或路径。");
        }
        try {
            Path relative = Path.of(value);
            if (relative.isAbsolute() || relative.getNameCount() != 1) {
                throw new ServiceException("INVALID_IMPORT_PATH", 400, "只允许导入目录直接包含的 CSV 文件名。");
            }
            for (Path part : relative) {
                if (part.toString().equals("..") || part.toString().equals(".")) {
                    throw new ServiceException("INVALID_IMPORT_PATH", 400, "导入路径不能包含 . 或 ..。");
                }
            }
            validateFileName(relative.getFileName().toString());
            Path realRoot = importRoot.toRealPath();
            Path candidate = realRoot.resolve(relative).normalize();
            if (!candidate.startsWith(realRoot)) {
                throw new ServiceException("INVALID_IMPORT_PATH", 400, "导入路径越过允许的目录。");
            }
            if (Files.isSymbolicLink(candidate)) {
                throw new ServiceException("INVALID_IMPORT_PATH", 400, "不允许通过符号链接导入文件。");
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(realRoot) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new ServiceException("INVALID_IMPORT_PATH", 400, "导入目标必须是允许目录中的普通文件。");
            }
            if (Files.size(real) > MAX_BYTES) {
                throw new ServiceException("FILE_TOO_LARGE", 413, "CSV 文件不能超过 5 MiB。");
            }
            return real;
        } catch (java.nio.file.InvalidPathException failure) {
            throw new ServiceException("INVALID_IMPORT_PATH", 400, "导入路径格式无效。");
        } catch (java.nio.file.NoSuchFileException failure) {
            throw new ServiceException("IMPORT_FILE_NOT_FOUND", 404, "指定的导入文件不存在。");
        } catch (IOException failure) {
            throw new ServiceException("FILE_READ_ERROR", 400, "指定的导入文件无法读取。", failure);
        }
    }

    private Fingerprint copy(InputStream input, Path output) throws IOException {
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[8192];
        try (OutputStream sink = Files.newOutputStream(output)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) {
                    throw new ServiceException("FILE_TOO_LARGE", 413, "CSV 文件不能超过 5 MiB。");
                }
                digest.update(buffer, 0, read);
                sink.write(buffer, 0, read);
            }
        }
        return new Fingerprint(total, HexFormat.of().formatHex(digest.digest()));
    }

    private Path privateTemp(String prefix, String suffix) throws IOException {
        try {
            return Files.createTempFile(storageRoot, prefix, suffix,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException unsupported) {
            return Files.createTempFile(storageRoot, prefix, suffix);
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
    }

    private void deleteOwned(Path path) {
        if (path != null && path.getParent().equals(storageRoot)) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException failure) {
                // Failure is observable in server logs; the caller's original error is preserved.
                org.slf4j.LoggerFactory.getLogger(FileStorage.class).warn("Could not remove temporary managed file", failure);
            }
        }
    }

    record ManagedFile(String storageName, Path path, long size, String sha256) { }
    private record Fingerprint(long size, String sha256) { }

    final class VerifiedFile implements AutoCloseable {
        private final Path path;
        private VerifiedFile(Path path) { this.path = path; }
        Path path() { return path; }
        @Override public void close() { deleteOwned(path); }
    }
}
