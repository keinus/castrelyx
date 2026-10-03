package org.castrelyx.castrelsign.kms;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

public final class ProtectedFiles {
  private ProtectedFiles() {}
  public static void restrict(Path path) throws IOException {
    var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (posix != null) {
      posix.setPermissions(Files.isDirectory(path) ? PosixFilePermissions.fromString("rwx------")
          : PosixFilePermissions.fromString("rw-------"));
      return;
    }
    var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (acl == null) throw new IOException("filesystem does not support private file permissions");
    acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
  }
  public static void write(Path target, byte[] data) throws IOException {
    Files.createDirectories(target.toAbsolutePath().getParent());
    Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), ".kms-", ".tmp");
    try {
      restrict(temp);
      try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      // A non-atomic fallback could leave key material or the rollback checkpoint torn.
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      restrict(target);
      if (Files.getFileAttributeView(target, PosixFileAttributeView.class) != null) {
        try (var dir = FileChannel.open(target.toAbsolutePath().getParent(), StandardOpenOption.READ)) { dir.force(true); }
      }
    } finally { Files.deleteIfExists(temp); }
  }
}
