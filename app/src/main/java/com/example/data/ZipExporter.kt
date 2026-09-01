package com.example.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipExporter {
    fun createTripZip(
        destZipFile: File,
        filesToInclude: List<File>
    ): File {
        if (destZipFile.exists()) {
            destZipFile.delete()
        }

        ZipOutputStream(FileOutputStream(destZipFile)).use { zos ->
            for (file in filesToInclude) {
                if (file.exists() && file.isFile) {
                    FileInputStream(file).use { fis ->
                        val entry = ZipEntry(file.name)
                        zos.putNextEntry(entry)
                        fis.copyTo(zos)
                        zos.closeEntry()
                    }
                }
            }
        }
        return destZipFile
    }
}
