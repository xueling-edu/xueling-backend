package com.hope.xueling.common.util;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;
/**
 * 阿里云OSS工具类
 * @author 谢光湘
 * @since 2026/1/24
 */
@Component
public class AliOSSUtils {
    /**
     * OSS 访问域名，如 https://oss-cn-hangzhou.aliyuncs.com
     */
    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    /**
     * OSS AccessKey ID
     */
    @Value("${aliyun.oss.accessKeyId}")
    private String accessKeyId;

    /**
     * OSS AccessKey Secret
     */
    @Value("${aliyun.oss.accessKeySecret}")
    private String accessKeySecret;

    /**
     * OSS 存储空间名称
     */
    @Value("${aliyun.oss.bucketName}")
    private String bucketName;

    /**
     * 上传用户头像到 user/avatar/{userId}/ 目录
     * @param multipartFile 上传的头像文件
     * @param userId 用户 ID
     * @return 图片访问 URL
     * @throws IOException 读取文件流失败
     */
    public String uploadAvatar(MultipartFile multipartFile, Long userId) throws IOException {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("用户 ID 无效");
        }
        String originalFilename = multipartFile.getOriginalFilename();
        int extensionStart = originalFilename == null ? -1 : originalFilename.lastIndexOf('.');
        String extension = extensionStart < 0 ? "" : originalFilename.substring(extensionStart);
        if (!extension.matches("\\.[a-zA-Z0-9]{1,10}")) {
            throw new IOException("头像文件扩展名无效");
        }
        String objectKey = "user/avatar/" + userId + "/" + UUID.randomUUID() + extension.toLowerCase(Locale.ROOT);

        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try (InputStream inputStream = multipartFile.getInputStream()) {
            ossClient.putObject(bucketName, objectKey, inputStream);
        } finally {
            ossClient.shutdown();
        }
        return endpoint.split("//")[0] + "//" + bucketName + "." + endpoint.split("//")[1] + "/" + objectKey;
    }

    /**
     * 上传文章图片到OSS
     * @param inputStream 输入流
     * @param originalFilename 原始文件名
     * @return 上传到OSS的文件路径
     * @throws IOException 如果上传过程中发生IO异常
     */
    public String upload(InputStream inputStream, String originalFilename) throws IOException {

        String fileName = UUID.randomUUID() + "-" + originalFilename+".png";

        //上传文件到 OSS
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        ossClient.putObject(bucketName,"read_material/article_image/"+fileName, inputStream);

        //文件访问路径
        String url = endpoint.split("//")[0] + "//" + bucketName + "." + endpoint.split("//")[1] + "/" + fileName;

        // 关闭ossClient
        ossClient.shutdown();
        return url;// 把上传到oss的路径返回
    }
}
