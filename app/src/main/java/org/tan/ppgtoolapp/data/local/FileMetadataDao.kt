package org.tan.ppgtoolapp.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FileMetadataDao {

    @Query("SELECT * FROM downloaded_files ORDER BY downloadTime DESC")
    suspend fun getAll(): List<FileMetadata>

    @Query("SELECT * FROM downloaded_files WHERE fileName = :fileName")
    suspend fun getByFileName(fileName: String): FileMetadata?

    @Query("SELECT EXISTS(SELECT 1 FROM downloaded_files WHERE fileName = :fileName)")
    suspend fun exists(fileName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: FileMetadata)

    @Delete
    suspend fun delete(metadata: FileMetadata)

    @Query("DELETE FROM downloaded_files WHERE fileName = :fileName")
    suspend fun deleteByFileName(fileName: String)

    @Query("DELETE FROM downloaded_files")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM downloaded_files")
    suspend fun count(): Int
}
