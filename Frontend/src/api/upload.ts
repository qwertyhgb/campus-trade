import { request } from './request'

export const uploadApi = {
  /** 上传图片（需登录），返回图片访问 URL，如 /upload/xxx.jpg */
  uploadImage: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    // 不手动设置 Content-Type，由浏览器附带 multipart boundary。
    return request.post<string>('/upload/image', formData)
  },
}
