# Local Cache

This topic describes the working principles of Local Cache and how to enable Local Cache to improve query performance on external data.

In data lake analytics, StarRocks works as an OLAP engine to scan data files stored in external storage systems, such as HDFS and Amazon S3. The I/O overhead increases as the number of files to scan increases. In addition, in some ad hoc scenarios, frequent access to the same data doubles I/O overhead.

To optimize the query performance in these scenarios, StarRocks 2.5 provides the Local Cache feature. This feature splits data in an external storage system into multiple blocks based on predefined policies and caches the data on StarRocks backends (BEs). This eliminates the need to pull data from external systems for each access request and accelerates queries and analysis on hot data. Local Cache only works when you query data from external storage systems by using external catalogs or external tables (excluding external tables for JDBC-compatible databases). It does not work when you query StarRocks native tables.

## How it works

StarRocks splits data in an external storage system into multiple blocks of the same size (1 MB by default), and caches the data on BEs. Block is the smallest unit of data cache.

For example, if you query a Parquet file of 128 MB from Amazon S3, StarRocks splits the file into 128 blocks (1 MB for each block, recommended setting). The blocks are [0, 1 MB), [1 MB, 2 MB), [2 MB, 3 MB) ... [127 MB, 128 MB). StarRocks assigns a globally unique ID to each block, called a cache key. A cache key consists of the following three parts.

```Plain
hash(filename) + filesize + blockId
```

The following table provides descriptions of each part.

| **Component item** | **Description**                                              |
| ------------------ | ------------------------------------------------------------ |
| filename           | The name of the data file.                                   |
| filesize           | The size of the data file, 1 MB by default.                  |
| blockId            | The ID that StarRocks assigns to a block when splitting the data file. The ID is unique under the same data file but is not unique within your StarRocks cluster. |

If the query hits the [1 MB, 2 MB) block, StarRocks performs the following operations:

1. Check whether the block exists in the cache.
2. If the block exists, StarRocks reads the block from the cache. If the block does not exist, StarRocks reads the block from Amazon S3 and caches it on a BE.

By default, StarRocks caches data blocks read from external storage systems. If you do not want to cache data blocks read from external storage systems, run the following command to disable data cache.

```SQL
SET enable_populate_block_cache = false;
```

For more information about `enable_populate_block_cache`, see [System variables](../reference/System_variable.md).

## Storage media of blocks

By default, StarRocks uses the memory of BE machines to cache blocks. It also supports using both the memory and disks as hybrid storage media of blocks. If BE machines are equipped with disks, such as NVMe drives or SSDs, you can use both the memory and disks to cache blocks. If you use cloud storage, such as Amazon EBS, for BE machines, we recommend that you use only the memory to cache blocks.

## Cache replacement policies

StarRocks uses the [least recently used](https://en.wikipedia.org/wiki/Cache_replacement_policies#Least_recently_used_(LRU)) (LRU) policy to cache and discard data.

- StarRocks reads data from memory, and then from disks if the data is not found in memory. Data read from disks is loaded into memory.
- Data deleted from memory is written into disks. Data removed from disks is discarded.

## Enable Local Cache

Local cache is disabled by default. To enable this feature, configure FEs and BEs in your StarRocks cluster.

### Configurations for FEs

You can enable Local Cache for FEs by using one of the following methods:

- Enable Local Cache for a given session based on your requirements.

  ```SQL
  SET enable_scan_block_cache = true;
  ```

- Enable Local Cache for all active sessions.

  ```SQL
  SET GLOBAL enable_scan_block_cache = true;
  ```

### Configurations for BEs

Add the following parameters to the **conf/be.conf** file of each BE. Then restart each BE to make the settings take effect.

| **Parameter**          | **Description**                                              |
| ---------------------- | ------------------------------------------------------------ |
| block_cache_enable     | Whether Local Cache is enabled.<ul><li>`true`: Local cache is enabled.</li><li>`false`: Local cache is disabled. The value of this parameter defaults to `false`.</li></ul>To enable Local Cache, set the value of this parameter to `true`. |
| block_cache_disk_path  | The paths of disks. We recommend that the number of paths you configured for this parameter is the same as the number of disks of your BE machine. Multiple paths need to be separated with semicolons (;). After you add this parameter, StarRocks automatically creates a file named **cachelib_data** to cache blocks. |
| block_cache_meta_path  | The storage path of block metadata. You can customize the storage path. We recommend that you store the metadata under the **$STARROCKS_HOME** path. |
| block_cache_mem_size   | The maximum amount of data that can be cached in the memory. Unit: bytes. The default value is `2147483648`, which is 2 GB. We recommend that you set the value of this parameter to at least 20 GB. If StarRocks reads a large amount of data from disks after Local Cache is enabled, consider increasing the value. |
| block_cache_disk_size  | The maximum amount of data that can be cached in a single disk. For example, if you configure two disk paths for the `block_cache_disk_path` parameter and set the value of the `block_cache_disk_size` parameter as `21474836480` (20 GB), a maximum of 40 GB data can be cached in these two disks. The default value is `0`, which indicates that only the memory is used to cache data. Unit: bytes. |

Examples of setting these parameters.

```Plain

# Enable Local Cache.
block_cache_enable = true  

# Configure the disk path. Assume the BE machine is equipped with two disks.
block_cache_disk_path = /home/disk1/sr/dla_cache_data/;/home/disk2/sr/dla_cache_data/

# Configure the metadata storage path.
block_cache_meta_path = /home/disk1/sr/dla_cache_meta/ 

# Set block_cache_mem_size to 2 GB.
block_cache_mem_size = 2147483648

# Set block_cache_disk_size to 1.2 TB.
block_cache_disk_size = 1288490188800
```

## Check whether a query hits local cache

You can check whether a query hits local cache by analyzing the following metrics in the query profile:

- `BlockCacheReadBytes`: indicates the amount of data that StarRocks reads directly from the memory and disks.
- `BlockCacheWriteBytes`: indicates the amount of data loaded from an external storage system to memory and disks.
- `BytesRead`: indicates the amount of data that StarRocks read from an external storage system.

Example 1: In this example, StarRocks reads a large amount of data (7.65 GB) from the external storage system and few data (518.73 MB) from the memory and disks. This means that few local caches were hit.

```Plain
 - Table: lineorder
 - BlockCacheReadBytes: 518.73 MB
   - __MAX_OF_BlockCacheReadBytes: 4.73 MB
   - __MIN_OF_BlockCacheReadBytes: 16.00 KB
 - BlockCacheReadCounter: 684
   - __MAX_OF_BlockCacheReadCounter: 4
   - __MIN_OF_BlockCacheReadCounter: 0
 - BlockCacheReadTimer: 737.357us
 - BlockCacheWriteBytes: 7.65 GB
   - __MAX_OF_BlockCacheWriteBytes: 64.39 MB
   - __MIN_OF_BlockCacheWriteBytes: 0.00 
 - BlockCacheWriteCounter: 7.887K (7887)
   - __MAX_OF_BlockCacheWriteCounter: 65
   - __MIN_OF_BlockCacheWriteCounter: 0
 - BlockCacheWriteTimer: 23.467ms
   - __MAX_OF_BlockCacheWriteTimer: 62.280ms
   - __MIN_OF_BlockCacheWriteTimer: 0ns
 - BufferUnplugCount: 15
   - __MAX_OF_BufferUnplugCount: 2
   - __MIN_OF_BufferUnplugCount: 0
 - BytesRead: 7.65 GB
   - __MAX_OF_BytesRead: 64.39 MB
   - __MIN_OF_BytesRead: 0.00
```

Example 2: In this example, the amount of data StarRocks reads directly from the external storage system is 0, which means StarRocks reads data only from local cache.

```Plain
- Table: lineorder
 - BlockCacheReadBytes: 7.37 GB
   - __MAX_OF_BlockCacheReadBytes: 60.73 MB
   - __MIN_OF_BlockCacheReadBytes: 16.00 KB
 - BlockCacheReadCounter: 8.571K (8571)
   - __MAX_OF_BlockCacheReadCounter: 68
   - __MIN_OF_BlockCacheReadCounter: 1
 - BlockCacheReadTimer: 174.362ms
   - __MAX_OF_BlockCacheReadTimer: 753.745ms
   - __MIN_OF_BlockCacheReadTimer: 15.840us
 - BlockCacheWriteBytes: 0.00 
 - BlockCacheWriteCounter: 0
 - BlockCacheWriteTimer: 0ns
 - BufferUnplugCount: 103
   - __MAX_OF_BufferUnplugCount: 5
   - __MIN_OF_BufferUnplugCount: 0
 - BytesRead: 0.00 
```
