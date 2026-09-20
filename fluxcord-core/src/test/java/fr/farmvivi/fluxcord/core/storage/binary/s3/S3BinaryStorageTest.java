package fr.farmvivi.fluxcord.core.storage.binary.s3;

import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link S3BinaryStorage} against a mocked SDK client: the requests it builds (bucket, key with the root prefix),
 * how S3 answers map to the {@code BinaryStorage} contract, and the bucket check at construction.
 */
class S3BinaryStorageTest {

    private final S3Client s3 = mock(S3Client.class);
    private final S3Presigner presigner = mock(S3Presigner.class);
    private final EventManager events = mock(EventManager.class);
    private S3BinaryStorage storage;

    @BeforeEach
    void setUp() {
        when(events.hasListeners(any())).thenReturn(false);
        when(s3.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        storage = new S3BinaryStorage("s3", s3, presigner, "bucket", "/fluxcord\\bots", events);
    }

    // ---- construction --------------------------------------------------------------------------------------------

    @Test
    void rootPrefixIsNormalisedAndPrependedToEveryKey() {
        assertEquals("", S3BinaryStorage.normalizePrefix(null));
        assertEquals("", S3BinaryStorage.normalizePrefix(""));
        assertEquals("a/b/", S3BinaryStorage.normalizePrefix("/a\\b"));
        assertEquals("a/", S3BinaryStorage.normalizePrefix("a/"));

        storage.fileExists(BinaryStorageKey.guild("g1", "covers/a.jpg"));
        ArgumentCaptor<HeadObjectRequest> head = ArgumentCaptor.forClass(HeadObjectRequest.class);
        verify(s3).headObject(head.capture());
        assertEquals("bucket", head.getValue().bucket());
        assertEquals("fluxcord/bots/guild:g1/covers/a.jpg", head.getValue().key());
    }

    @Test
    void missingBucketIsCreatedAndAnUnreachableOneFails() {
        S3Client fresh = mock(S3Client.class);
        when(fresh.headBucket(any(HeadBucketRequest.class))).thenThrow(NoSuchBucketException.builder().build());
        new S3BinaryStorage("s3", fresh, presigner, "new-bucket", "", events);
        ArgumentCaptor<CreateBucketRequest> create = ArgumentCaptor.forClass(CreateBucketRequest.class);
        verify(fresh).createBucket(create.capture());
        assertEquals("new-bucket", create.getValue().bucket());

        S3Client down = mock(S3Client.class);
        when(down.headBucket(any(HeadBucketRequest.class))).thenThrow(S3Exception.builder().message("403").build());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new S3BinaryStorage("s3", down, presigner, "b", "", events));
        assertTrue(e.getMessage().contains("Unable to reach S3 bucket b"), e.getMessage());

        S3Client cannotCreate = mock(S3Client.class);
        when(cannotCreate.headBucket(any(HeadBucketRequest.class))).thenThrow(NoSuchBucketException.builder().build());
        when(cannotCreate.createBucket(any(CreateBucketRequest.class))).thenThrow(S3Exception.builder().message("denied").build());
        assertThrows(IllegalStateException.class, () -> new S3BinaryStorage("s3", cannotCreate, presigner, "b", "", events));
    }

    // ---- reads ---------------------------------------------------------------------------------------------------

    @Test
    void existenceSizeAndDateComeFromHeadObject() {
        BinaryStorageKey key = BinaryStorageKey.global("a.txt");
        HeadObjectResponse head = HeadObjectResponse.builder().contentLength(42L)
                .lastModified(Instant.parse("2026-09-20T10:00:00Z")).build();
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(head);

        assertTrue(storage.fileExists(key));
        assertEquals(42L, storage.getFileSize(key));
        assertEquals(Instant.parse("2026-09-20T10:00:00Z").toEpochMilli(), storage.getLastModifiedTime(key));

        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertFalse(storage.fileExists(key));
        assertEquals(-1, storage.getFileSize(key));
        assertEquals(-1, storage.getLastModifiedTime(key));
        assertTrue(storage.getInputStream(key).isEmpty());
        assertTrue(storage.getPublicUrl(key, 60).isEmpty());

        reset(s3);
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().message("boom").build());
        assertFalse(storage.fileExists(key), "any other failure reads as absent, logged");
        assertEquals(-1, storage.getFileSize(key));
    }

    @Test
    void inputStreamAndDownloadReadTheObject() throws Exception {
        BinaryStorageKey key = BinaryStorageKey.user("u1", "notes.txt");
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(inv -> new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)))));

        try (InputStream in = storage.getInputStream(key).orElseThrow()) {
            assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        ArgumentCaptor<GetObjectRequest> get = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(get.capture());
        assertEquals("fluxcord/bots/user:u1/notes.txt", get.getValue().key());
    }

    @Test
    void downloadWritesTheObjectToDisk(@TempDir Path dir) throws Exception {
        BinaryStorageKey key = BinaryStorageKey.global("a.bin");
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(inv -> new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[]{1, 2, 3}))));

        Path dest = dir.resolve("sub").resolve("a.bin");
        assertTrue(storage.downloadFile(key, dest.toFile()), "parent directories are created");
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(dest));
    }

    // ---- writes --------------------------------------------------------------------------------------------------

    @Test
    void saveFileUploadsTheBytesAsOnePutObjectOnClose() throws Exception {
        BinaryStorageKey key = BinaryStorageKey.guild("g1", "img/pic.png");
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        AtomicReference<PutObjectRequest> request = new AtomicReference<>();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(inv -> {
            request.set(inv.getArgument(0));
            try (InputStream in = inv.getArgument(1, RequestBody.class).contentStreamProvider().newStream()) {
                uploaded.set(in.readAllBytes());
            }
            return PutObjectResponse.builder().build();
        });

        byte[] payload = "png-bytes".repeat(500).getBytes(StandardCharsets.UTF_8);
        assertTrue(storage.saveFile(key, new ByteArrayInputStream(payload), false));

        assertArrayEquals(payload, uploaded.get());
        assertEquals("fluxcord/bots/guild:g1/img/pic.png", request.get().key());
        assertEquals("image/png", request.get().contentType());
    }

    @Test
    void aFailedUploadIsReportedToTheWriter() {
        BinaryStorageKey key = BinaryStorageKey.global("a.txt");
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(S3Exception.builder().message("quota").build());

        assertFalse(storage.saveFile(key, new ByteArrayInputStream(new byte[]{1}), true),
                "before: the upload ran on a background thread and saveFile answered true whatever happened");
    }

    @Test
    void saveWithoutOverwriteRefusesAnExistingObject() {
        BinaryStorageKey key = BinaryStorageKey.global("a.txt");
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        assertNull(storage.getOutputStream(key, false));
        assertFalse(storage.saveFile(key, new ByteArrayInputStream(new byte[0]), false));
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void deleteGoesThroughTheContractAndTheClient() {
        BinaryStorageKey key = BinaryStorageKey.global("a.txt");
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        assertTrue(storage.deleteFile(key));
        ArgumentCaptor<DeleteObjectRequest> delete = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(delete.capture());
        assertEquals("fluxcord/bots/global/a.txt", delete.getValue().key());

        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenThrow(S3Exception.builder().message("x").build());
        assertFalse(storage.deleteFile(key));

        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        assertFalse(storage.deleteFile(key), "absent: nothing to delete");
    }

    // ---- directories and listing --------------------------------------------------------------------------------

    @Test
    void listFilesReturnsPathsRelativeToTheScope() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("fluxcord/bots/guild:g1/covers/").build(),
                        S3Object.builder().key("fluxcord/bots/guild:g1/covers/a.jpg").build(),
                        S3Object.builder().key("fluxcord/bots/guild:g1/covers/b.jpg").build())
                .build());

        List<String> files = storage.listFiles(BinaryStorageKey.guild("g1", "covers"));

        assertEquals(List.of("covers/a.jpg", "covers/b.jpg"), files, "the directory marker itself is skipped");
        ArgumentCaptor<ListObjectsV2Request> list = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3).listObjectsV2(list.capture());
        assertEquals("fluxcord/bots/guild:g1/covers/", list.getValue().prefix());
        assertEquals("/", list.getValue().delimiter());

        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(S3Exception.builder().message("x").build());
        assertTrue(storage.listFiles(BinaryStorageKey.guild("g1", "covers")).isEmpty());
    }

    @Test
    void directoriesAreEmptyMarkerObjectsOrNonEmptyPrefixes() {
        BinaryStorageKey key = BinaryStorageKey.global("folder");
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(PutObjectResponse.builder().build());
        assertTrue(storage.createDirectory(key));
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertEquals("fluxcord/bots/global/folder/", put.getValue().key());

        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        assertTrue(storage.isDirectory(key), "marker object");

        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(S3Object.builder().key("fluxcord/bots/global/folder/x").build()).build());
        assertTrue(storage.isDirectory(key), "no marker but objects under the prefix");
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder().build());
        assertFalse(storage.isDirectory(key));

        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenThrow(S3Exception.builder().message("x").build());
        assertFalse(storage.createDirectory(key));
    }

    // ---- public URL and close -------------------------------------------------------------------------------------

    @Test
    void publicUrlIsAPresignedGetWithTheRequestedLifetime() {
        BinaryStorageKey key = BinaryStorageKey.global("a.txt");
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().build());
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenAnswer(inv -> URI.create("https://s3/bucket/a.txt?sig").toURL());
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

        assertEquals(Optional.of("https://s3/bucket/a.txt?sig"), storage.getPublicUrl(key, 120));
        ArgumentCaptor<GetObjectPresignRequest> presign = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(presign.capture());
        assertEquals(120, presign.getValue().signatureDuration().toSeconds());
        assertEquals("fluxcord/bots/global/a.txt", presign.getValue().getObjectRequest().key());

        storage.getPublicUrl(key, 0);
        verify(presigner, times(2)).presignGetObject(presign.capture());
        assertEquals(3600, presign.getValue().signatureDuration().toSeconds(), "default lifetime");

        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenThrow(new IllegalStateException("x"));
        assertTrue(storage.getPublicUrl(key, 1).isEmpty());
    }

    @Test
    void closeReleasesBothClients() {
        assertTrue(storage.close());
        verify(s3).close();
        verify(presigner).close();
    }
}
