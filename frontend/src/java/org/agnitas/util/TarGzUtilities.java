/*

    Copyright (C) 2022 AGNITAS AG (https://www.agnitas.org)

    This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
    You should have received a copy of the GNU Affero General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.

*/

package org.agnitas.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TarGzUtilities {
	/** The logger. */
	private static final transient Logger LOGGER = LogManager.getLogger(TarGzUtilities.class);
	
	public static void decompress(File tarGzFile, File decompressToPath) throws Exception {
		if (!tarGzFile.exists()) {
			throw new Exception("TarGz file does not exist: " + tarGzFile.getAbsolutePath());
		} else if (!tarGzFile.isFile()) {
			throw new Exception("TarGz file path is not a file: " + tarGzFile.getAbsolutePath());
		} else if (decompressToPath.exists()) {
			throw new Exception("Destination path already exists: " + decompressToPath.getAbsolutePath());
		} else {
			try (TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(tarGzFile))))) {
				decompressToPath.mkdirs();
				
				TarArchiveEntry entry;
				while ((entry = tarArchiveInputStream.getNextTarEntry()) != null) {
					if (entry.isDirectory()) {
						continue;
					}
					String entryFilePath = entry.getName();
					entryFilePath = entryFilePath.replace("\\", "/");
					if (entryFilePath.startsWith("/") || entryFilePath.startsWith("../") || entryFilePath.endsWith("/..") || entryFilePath.contains("/../")) {
						throw new Exception("Traversal error in tar gz file: " + tarGzFile.getAbsolutePath());
					}
					File currentfile = new File(decompressToPath, entryFilePath);
					if (!currentfile.getCanonicalPath().startsWith(decompressToPath.getCanonicalPath())) {
						throw new Exception("Traversal error in tar gz file: " + tarGzFile.getAbsolutePath() + "/");
					}
					File parent = currentfile.getParentFile();
					if (!parent.exists()) {
						parent.mkdirs();
					}
					
					try(final FileOutputStream fileOutputStream = new FileOutputStream(currentfile)) {
						IOUtils.copy(tarArchiveInputStream, fileOutputStream);
					}
				}
			} catch (Exception e) {
				try {
					if (decompressToPath.exists()) {
						FileUtils.deleteDirectory(decompressToPath);
					}
				} catch (Exception e2) {
					LOGGER.error("Error deleting temporary directory", e2);
					
					// do nothing else
				}
			
				throw new Exception("Cannot decompress '" + tarGzFile + "'", e);
			}
		}
	}

	public static void compress(File tarGzFile, File fileToCompress, final String filePathInTarGzFile) throws IOException {
		if (tarGzFile.exists()) {
			throw new IOException("TarGz file already exists: " + tarGzFile.getAbsolutePath());
		} else if (!tarGzFile.getParentFile().exists()) {
			throw new IOException("Parent directory for TarGz file does not exist: " + tarGzFile.getParentFile().getAbsolutePath());
		}
		
		try (TarArchiveOutputStream tarArchiveOutputStream = new TarArchiveOutputStream(new GzipCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(tarGzFile))));
				FileInputStream fileInputStream = new FileInputStream(fileToCompress)) {
			tarArchiveOutputStream.putArchiveEntry(new TarArchiveEntry(fileToCompress, filePathInTarGzFile));
			IOUtils.copy(fileInputStream, tarArchiveOutputStream);
			tarArchiveOutputStream.closeArchiveEntry();
			tarArchiveOutputStream.finish();
		} catch (IOException e) {
			if (tarGzFile.exists()) {
				tarGzFile.delete();
			}
			throw e;
		}
	}

	public static InputStream openCompressedFile(final File tarGzFile) throws Exception {
		return openCompressedFile(tarGzFile, null);
	}
	
	public static InputStream openCompressedFile(final File tarGzFile, final String filePathInTarGzFile) throws Exception {
		if (!tarGzFile.exists()) {
			throw new Exception("TarGz file does not exist: " + tarGzFile.getAbsolutePath());
		} else if (!tarGzFile.isFile()) {
			throw new Exception("TarGz file path is not a file: " + tarGzFile.getAbsolutePath());
		} else {
			TarArchiveInputStream tarArchiveInputStream = null;
			try {
				tarArchiveInputStream = new TarArchiveInputStream(new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(tarGzFile))));
				TarArchiveEntry entry = null;
				while ((entry = tarArchiveInputStream.getNextTarEntry()) != null) {
					if (entry.isDirectory()) {
						continue;
					} else {
						if (StringUtils.isBlank(filePathInTarGzFile)) {
							return tarArchiveInputStream;
						} else {
							String entryFilePath = entry.getName();
							entryFilePath = entryFilePath.replace("\\", "/");
							if (entryFilePath.equals(filePathInTarGzFile)) {
								return tarArchiveInputStream;
							}
						}
					}
				}
				
				if (StringUtils.isBlank(filePathInTarGzFile)) {
					throw new Exception("TarGz file '" + tarGzFile.getAbsolutePath() + "' is empty");
				} else {
					throw new Exception("TarGz file does not contain file '" + filePathInTarGzFile + "'");
				}
			} catch (final Exception e) {
				try {
					if (tarArchiveInputStream != null) {
						tarArchiveInputStream.close();
					}
				} catch (@SuppressWarnings("unused") final IOException e1) {
					// Do nothing
				}
				throw e;
			}
		}
	}
}
