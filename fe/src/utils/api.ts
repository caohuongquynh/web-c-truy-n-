import axios from 'axios';

// Base API URL pointing to the Spring Boot backend
const API_BASE_URL = '/api';

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor to automatically attach JWT token if it exists in localStorage
api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// Response interceptor for handling common error responses
api.interceptors.response.use(
  (response) => response,
  (error) => {
    // Check if error is due to authentication / expired token
    if (error.response && error.response.status === 401) {
      // Clear expired credentials
      localStorage.removeItem('token');
      localStorage.removeItem('username');
      localStorage.removeItem('role');

      // Optionally trigger a page reload or event to redirect to login
      if (window.location.pathname !== '/login') {
        // Since we are using standard navigation without complex router library, we can reload or dispatch custom event
        window.dispatchEvent(new CustomEvent('unauthorized-api-call'));
      }
    }
    return Promise.reject(error);
  }
);

/**
 * Uploads a single image file to the backend, which forwards it to Cloudinary
 * and returns the resulting secure URL.
 *
 * NOTE: the shared `api` instance sets a default 'Content-Type: application/json'
 * header on every request. That default does NOT get replaced automatically just
 * because we send a FormData body, so we explicitly clear it here (set to
 * `undefined`) — that lets the browser compute the correct
 * 'multipart/form-data; boundary=...' header itself. Without this, the backend
 * sees 'application/json' and rejects the request with
 * "Current request is not a multipart request".
 */
export async function uploadImage(file: File): Promise<string> {
  if (!file.type.startsWith('image/')) {
    throw new Error('Vui lòng chọn một tệp ảnh hợp lệ.');
  }
  if (file.size > 10 * 1024 * 1024) {
    throw new Error('Ảnh vượt quá giới hạn 10 MB.');
  }
  const formData = new FormData();
  formData.append('file', file);

  const response = await api.post('/upload', formData, {
    headers: { 'Content-Type': undefined },
  });
  const url = response.data?.url;
  if (!url) {
    throw new Error(response.data?.error || 'Upload thất bại: không nhận được URL ảnh.');
  }
  return url;
}

export default api;
