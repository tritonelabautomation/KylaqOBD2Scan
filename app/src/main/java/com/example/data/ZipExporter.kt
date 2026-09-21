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
            val seen = mutableSetOf<String>()
            for (file in filesToInclude) {
                if (file.exists() && file.isFile) {
                    // Entries are flat (file.name only), so two directories can hand over
                    // the same name - the journal working dir and a finalized session dir
                    // both carry "<id>_transactions.csv". A duplicate name made
                    // putNextEntry throw and killed EVERY Drive backup since the journal
                    // shipped (owner 2026-09-21: "Backup failed: duplicate entry",
                    // "Last synchronized: Never"). First copy wins; a same-named second
                    // file is the same logical content, never worth dying for.
                    if (!seen.add(file.name)) continue
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
