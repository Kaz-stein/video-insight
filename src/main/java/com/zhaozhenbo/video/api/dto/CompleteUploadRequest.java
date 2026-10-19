package com.zhaozhenbo.video.api.dto;

/**
 * 合并上传的请求。
 *
 * expectedSha256 可选：调用方如果手里有整文件的 sha256，就传进来，
 * 服务端合并完会自己再算一遍对比，对不上直接丢弃合并结果。
 * 网页端不传，因为浏览器算 1GB 文件的哈希要把整份内容读进内存。
 */
public record CompleteUploadRequest(String title, String expectedSha256) {
}
