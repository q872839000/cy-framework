# cy-common-filestore

文件存储通用组件，提供统一的文件上传、下载、删除、元数据查询能力。内置本地文件系统与 MinIO 对象存储两种实现，并通过 Java SPI 机制支持第三方存储扩展（OSS、S3、Azure Blob 等）。

## 能力

- 多存储实现：本地文件系统、MinIO 对象存储，SPI 可插拔扩展
- 统一的文件键（fileKey）规则：业务前缀 + 日期目录 + UUID 文件名，保留原始扩展名
- 路径安全：fileKey 规范化与越界校验，拒绝 `..` 父级目录穿越
- 并发安全：本地存储基于 `Files` 原子操作，`CREATE_NEW` 防止覆盖；MinIO 客户端线程安全
- 资源管理：`FileResource` 封装文件流与元数据，配合 try-with-resources 自动释放
- 流式处理：上传下载全程走流，不做内存缓存，避免大文件 OOM
- 统一异常：所有存储异常以 `CYException` 抛出，错误码与文案在 `error-messages-filestore.properties` 单点定义
- 零配置启动：Spring Boot 自动装配，默认使用本地文件存储

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.cy.commons</groupId>
    <artifactId>cy-common-filestore</artifactId>
    <version>1.0.0</version>
</dependency>
```

使用 MinIO 存储时，需额外引入 MinIO 客户端（组件中以 optional 声明，不传递）：

```xml
<dependency>
    <groupId>io.minio</groupId>
    <artifactId>minio</artifactId>
    <version>8.5.7</version>
</dependency>
```

### 2. 配置

本地文件存储（默认，可省略整段配置）：

```yaml
cy:
  filestore:
    type: local
    local:
      base-path: ./data/files
```

MinIO 对象存储：

```yaml
cy:
  filestore:
    type: minio
    minio:
      url: http://127.0.0.1:9000
      access-key: minioadmin
      secret-key: minioadmin
      bucket-name: cy-files
      secure: false
```

### 3. 使用

注入 `FileServiceFacade` 即可：

```java
@Service
public class DemoService {

    private final FileServiceFacade fileServiceFacade;

    public DemoService(FileServiceFacade fileServiceFacade) {
        this.fileServiceFacade = fileServiceFacade;
    }

    public FileInfo upload(MultipartFile file) throws IOException {
        FileUploadRequest request = FileUploadRequest.builder()
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .size(file.getSize())
                .inputStream(file.getInputStream())
                .fileKey("demo")                // 可选，业务目录前缀
                .build();
        return fileServiceFacade.upload(request);
    }

    public void download(String fileKey, HttpServletResponse response) throws IOException {
        try (FileResource resource = fileServiceFacade.downloadAsResource(fileKey);
             InputStream is = resource.getInputStream()) {
            response.setContentType(resource.getContentType());
            response.setContentLengthLong(resource.getSize());
            is.transferTo(response.getOutputStream());
        }
    }
}
```

## API 说明

`FileServiceFacade` 是组件的唯一入口，方法如下：

| 方法 | 说明 |
|------|------|
| `upload(FileUploadRequest)` | 上传文件，返回 `FileInfo` |
| `downloadAsResource(String)` | 下载文件，返回 `FileResource`（含流与元数据，需关闭） |
| `downloadToFile(String, Path)` | 下载文件到本地路径 |
| `stat(String)` | 查询文件元数据（大小、内容类型），不打开文件流 |
| `delete(String)` | 删除文件，文件不存在时视为成功 |
| `exists(String)` | 检查文件是否存在 |
| `getStorageType()` | 获取当前存储类型 |

`FileUploadRequest` 字段：

| 字段 | 说明 |
|------|------|
| `originalFilename` | 原始文件名，用于保留扩展名 |
| `contentType` | 文件 MIME 类型 |
| `size` | 文件大小（字节）；未知时传 -1，MinIO 按分片上传 |
| `inputStream` | 文件输入流，上传完成后由组件关闭 |
| `fileKey` | 可选，业务目录前缀；为空时按 `yyyy/MM/dd` 日期目录生成 |
| `extraMetadata` | 可选，附加元数据 |

`FileInfo` 字段：`fileKey`、`size`、`contentType`、`originalFilename`（仅上传时可用）、`metadata`。

## 配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `cy.filestore.type` | `local` | 存储类型：`local`、`minio` 或 SPI 扩展类型 |
| `cy.filestore.local.base-path` | `./data/files` | 本地存储根目录 |
| `cy.filestore.minio.url` | — | MinIO 服务地址，支持带协议的完整地址 |
| `cy.filestore.minio.access-key` | — | 访问密钥 |
| `cy.filestore.minio.secret-key` | — | 秘密密钥 |
| `cy.filestore.minio.bucket-name` | — | 存储桶名称，不存在时自动创建 |
| `cy.filestore.minio.secure` | `false` | 是否使用 HTTPS，仅当 `url` 未带协议时生效 |

## 资源关闭约定

`downloadAsResource` 返回的 `FileResource` 持有底层文件流，必须由调用方关闭。推荐 try-with-resources：

```java
try (FileResource resource = fileServiceFacade.downloadAsResource(fileKey)) {
    InputStream stream = resource.getInputStream();
    // 使用流...
}
```

若只需文件大小、内容类型等元数据，使用 `stat(fileKey)`，不打开文件流。

## SPI 扩展第三方存储

以 OSS 为例，三步接入新存储类型。

1. 实现 `FileStorageStrategyProvider`：

```java
public class OssStorageStrategyProvider implements FileStorageStrategyProvider {

    @Override
    public String getType() {
        return "oss";
    }

    @Override
    public FileStorageStrategy create(FileStoreProperties properties) {
        return new OssFileStorageStrategy(properties);
    }

    @Override
    public boolean isSupported() {
        try {
            Class.forName("com.aliyun.oss.OSS");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
```

`isSupported()` 用于探测存储客户端依赖是否存在，依赖缺失时该提供者被跳过。

2. 在 `META-INF/services/com.cy.filestore.spi.FileStorageStrategyProvider` 中注册实现类全名。

3. 配置启用：

```yaml
cy:
  filestore:
    type: oss
```

自定义存储策略需实现 `FileStorageStrategy` 接口的全部方法（上传、下载、元数据查询、删除、存在性检查、存储类型）。

也可以在 Spring 容器中直接注册一个 `FileStorageStrategy` Bean，优先级高于 SPI 工厂创建的内置实现；同理可注册自定义 `FileServiceFacade` Bean 覆盖默认门面。

## 错误码

错误码范围 32000-32099，文案定义在 `error-messages-filestore.properties`：

| 错误码 | 说明 |
|--------|------|
| 32000 | 文件上传失败 |
| 32001 | 文件下载失败 |
| 32002 | 文件删除失败 |
| 32003 | 文件不存在 |
| 32004 | 文件已存在，禁止覆盖 |
| 32005 | 不支持的文件存储类型 |
| 32006 | MinIO 配置不完整 |
| 32007 | 目标不是文件，无法删除 |
| 32008 | 本地文件存储配置不完整 |

参数校验类问题（请求为空、fileKey 为空、路径越界等）抛出 `IllegalArgumentException`，不占用错误码。

## 结构

```
com.cy.filestore
├── FileServiceFacade               文件服务门面（唯一入口）
├── config
│   ├── FileStoreAutoConfiguration  Spring Boot 自动配置
│   └── FileStoreProperties         配置属性（cy.filestore.*）
├── resource
│   └── FileResource                文件资源（流 + 元数据，可关闭）
├── spi
│   ├── FileStorageStrategyFactory   SPI 工厂
│   ├── FileStorageStrategyProvider  SPI 提供者接口
│   └── impl                         内置 local / minio 提供者
├── storage
│   ├── FileStorageStrategy          存储策略接口
│   └── impl                         本地 / MinIO 实现
├── utils
│   └── FileKeyUtils                 文件键生成与路径安全校验
└── vo
    ├── FileInfo                     文件信息
    └── FileUploadRequest            上传请求
```

设计要点：

- 策略模式：`FileStorageStrategy` 定义存储接口，实现可插拔。
- 门面模式：`FileServiceFacade` 屏蔽策略细节，做统一参数校验。
- 工厂 + SPI：`FileStorageStrategyFactory` 通过 `ServiceLoader` 加载提供者，按配置类型创建策略。

## 许可证

Copyright © 2026 CY. All rights reserved.
