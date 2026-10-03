package kr.statusboard.nativeapp

import android.content.Context
import java.io.File

object FleetPrivateFiles {
    fun clear(context: Context) {
        val cache = context.cacheDir.canonicalFile
        listOf("documents", "backups", "camera").forEach { name ->
            val directory = File(cache, name).canonicalFile
            if (directory.parentFile == cache) directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        }
    }
}
