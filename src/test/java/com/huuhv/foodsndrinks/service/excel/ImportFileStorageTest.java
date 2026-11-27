package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.UploadProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;

/** {@link ImportFileStorage} trên thư mục tạm thật. */
class ImportFileStorageTest {

    @TempDir Path base;

    private Path importDir;
    private Path uploadDir;
    private ImportFileStorage storage;

    @BeforeEach
    void setUp() {
        importDir = base.resolve("import-tmp");
        uploadDir = base.resolve("uploads");
        storage = storage(importDir, uploadDir);
    }

    @Test
    void createsTheDirectoryAtStartup() {
        assertThat(importDir).isDirectory();
    }

    @Test
    void store_usesAServerGeneratedName_neverTheClientFileName() throws IOException {
        String name = storage.store(upload("../../uploads/evil.xlsx", new byte[]{1, 2, 3}));

        assertThat(name).matches("[0-9a-f-]{36}\\.xlsx");
        assertThat(storage.exists(name)).isTrue();
        assertThat(storage.resolve(name)).hasBinaryContent(new byte[]{1, 2, 3}).hasParent(importDir.toAbsolutePath());
        // Không có gì được ghi ra ngoài thư mục import
        assertThat(uploadDir).doesNotExist();
        assertThat(Files.list(importDir)).hasSize(1);
        // Mỗi lần lưu một tên khác nhau
        assertThat(storage.store(upload("a.xlsx", new byte[]{9}))).isNotEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../uploads/a.xlsx", "..\\uploads\\a.xlsx", "/etc/passwd", "a.xlsx", "",
            "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d.xls", "3F2B1C4D-5E6F-4A7B-8C9D-0E1F2A3B4C5D.xlsx",
            "sub/3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d.xlsx", "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d.xlsx\0.png"})
    void resolve_rejectsAnythingThatIsNotAStoredName(String name) {
        // Kể cả khi giá trị stored_file trong DB bị sửa: không bao giờ đọc/xoá file ngoài thư mục import
        assertThatIllegalArgumentException().isThrownBy(() -> storage.resolve(name));
        assertThatIllegalArgumentException().isThrownBy(() -> storage.exists(name));
        assertThatNoException().isThrownBy(() -> storage.delete(name));
    }

    @Test
    void resolve_rejectsNull() {
        assertThatIllegalArgumentException().isThrownBy(() -> storage.resolve(null));
    }

    @Test
    void delete_removesTheFile_andIsIdempotent() throws IOException {
        String name = storage.store(upload("a.xlsx", new byte[]{1}));

        storage.delete(name);
        storage.delete(name);

        assertThat(storage.exists(name)).isFalse();
        assertThat(storage.exists(UUID.randomUUID() + ".xlsx")).isFalse();
    }

    @Test
    void deleteOlderThan_removesOnlyOldStoredFiles_andLeavesForeignFilesAlone() throws IOException {
        String old = storage.store(upload("a.xlsx", new byte[]{1}));
        String fresh = storage.store(upload("b.xlsx", new byte[]{2}));
        Path foreign = Files.write(importDir.resolve("ghi-chu.xlsx"), new byte[]{3});
        Path keep = Files.write(importDir.resolve(".gitignore"), new byte[]{4});
        FileTime twoHoursAgo = FileTime.from(Instant.now().minus(Duration.ofHours(2)));
        Files.setLastModifiedTime(storage.resolve(old), twoHoursAgo);
        Files.setLastModifiedTime(foreign, twoHoursAgo);
        Files.setLastModifiedTime(keep, twoHoursAgo);

        assertThat(storage.deleteOlderThan(Duration.ofHours(1))).isEqualTo(1);

        assertThat(storage.exists(old)).isFalse();
        assertThat(storage.exists(fresh)).isTrue();
        assertThat(foreign).exists();
        assertThat(keep).exists();
        assertThat(storage.deleteOlderThan(Duration.ofHours(1))).isZero();
    }

    @Test
    void directoryInsideOrAroundThePublicUploadDirectory_failsStartup() {
        // Thư mục upload được phục vụ public tại /uploads/** — file import không bao giờ được nằm trong đó
        assertThatIllegalStateException().isThrownBy(() -> storage(uploadDir.resolve("import"), uploadDir))
                .withMessageContaining("app.excel.import.dir").withMessageContaining("app.upload.dir");
        assertThatIllegalStateException().isThrownBy(() -> storage(uploadDir, uploadDir));
        assertThatIllegalStateException().isThrownBy(() -> storage(base, uploadDir));
        assertThatIllegalStateException().isThrownBy(() -> storage(uploadDir.resolve("..").resolve("uploads"), uploadDir));
    }

    @Test
    void pathThatIsNotADirectory_failsStartup() throws IOException {
        Path file = Files.write(base.resolve("a-file"), new byte[]{1});

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> storage(file, uploadDir)))
                .isInstanceOfAny(IllegalStateException.class, java.io.UncheckedIOException.class);
    }

    private static ImportFileStorage storage(Path importDir, Path uploadDir) {
        return new ImportFileStorage(ExcelTestFiles.propertiesWithImportDir(importDir),
                new UploadProperties(uploadDir.toString()));
    }
}
