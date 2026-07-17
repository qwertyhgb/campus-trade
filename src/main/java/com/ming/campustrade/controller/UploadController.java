package com.ming.campustrade.controller;

import com.ming.campustrade.common.Result;
import com.ming.campustrade.common.ResultCode;
import com.ming.campustrade.common.exception.BusinessException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Tag(name = "文件上传", description = "图片上传接口，上传后返回可访问的图片URL")
@RestController
@RequestMapping("/upload")
public class UploadController {

    /**
     * 允许上传的图片文件扩展名集合。
     * 仅支持常见的 Web 图片格式，防止上传非预期的文件类型。
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp"
    );

    /** 文件上传存储的本地目录路径，从配置项 upload.path 读取 */
    @Value("${upload.path}")
    private String uploadPath;

    /** 文件上传后对外可访问的 URL 前缀，从配置项 upload.url-prefix 读取 */
    @Value("${upload.url-prefix}")
    private String urlPrefix;

    @Operation(summary = "上传图片", description = "上传图片文件，支持 jpg/jpeg/png/gif/webp 格式，返回图片访问URL")
    @PostMapping("/image")
    public Result<String> uploadImage(@Parameter(description = "上传的图片文件") @RequestParam("file") MultipartFile file) {

        // ========== 1. 校验文件是否为空 ==========
        if (file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }

        // ========== 2. 获取原始文件名并校验 ==========
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件名不能为空");
        }

        // ========== 3. 校验文件扩展名 ==========
        // 从原始文件名中提取扩展名，转为小写后与白名单比对
        int lastDotIndex = originalFilename.lastIndexOf(".");
        if (lastDotIndex < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件格式不正确");
        }
        String extension = originalFilename.substring(lastDotIndex).toLowerCase();

        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "只支持 jpg/jpeg/png/gif/webp 格式");
        }

        // ========== 4. 校验 MIME 类型 ==========
        // 通过 Content-Type 二次校验，防止篡改扩展名上传非图片文件
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "只支持图片文件");
        }

        // ========== 5. 生成唯一文件名并保存 ==========
        // 使用 UUID 生成唯一名称，避免文件名冲突和覆盖
        String newFilename = UUID.randomUUID().toString().replace("-", "") + extension;

        // 确保上传目录存在，不存在则自动创建（含父目录）
        File uploadDir = new File(uploadPath);
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();
        }

        // 将上传文件写入磁盘
        File destFile = new File(uploadDir, newFilename);
        try {
            file.transferTo(destFile);
        } catch (IOException e) {
            log.error("文件保存失败：original={}, dest={}", originalFilename, destFile.getAbsolutePath(), e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "文件保存失败");
        }

        // ========== 6. 组装可访问的图片 URL 并返回 ==========
        String url = urlPrefix + "/" + newFilename;
        log.info("图片上传成功：original={}, url={}", originalFilename, url);
        return Result.success(url);
    }
}
