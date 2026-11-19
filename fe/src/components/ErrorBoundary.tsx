import { Component, ErrorInfo, ReactNode } from 'react';
import { AlertTriangle, RefreshCw, Home } from 'lucide-react';

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export default class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, error: null };

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    // Log for debugging; could be wired to a backend logging endpoint later.
    console.error('Uncaught error caught by ErrorBoundary:', error, errorInfo);
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null });
  };

  handleGoHome = () => {
    localStorage.setItem('lastView', 'home');
    localStorage.removeItem('lastMangaId');
    localStorage.removeItem('lastChapterId');
    window.location.href = '/';
  };

  render() {
    if (this.state.hasError) {
      return (
        <div className="min-h-screen flex items-center justify-center bg-neutral-950 px-4">
          <div className="max-w-md w-full bg-neutral-900 border border-neutral-800 rounded-lg p-8 text-center shadow-lg">
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-orange-500/10">
              <AlertTriangle className="h-7 w-7 text-orange-500" />
            </div>
            <h1 className="text-lg font-black text-white mb-2">
              Đã xảy ra lỗi không mong muốn
            </h1>
            <p className="text-sm text-neutral-400 mb-6">
              Ứng dụng gặp sự cố khi hiển thị trang này. Bạn có thể thử tải lại
              hoặc quay về trang chủ.
            </p>

            {this.state.error && (
              <pre className="mb-6 max-h-32 overflow-auto rounded border border-neutral-800 bg-neutral-950 p-3 text-left text-[11px] text-neutral-500 whitespace-pre-wrap break-words">
                {this.state.error.message}
              </pre>
            )}

            <div className="flex flex-col sm:flex-row gap-3 justify-center">
              <button
                onClick={this.handleReset}
                className="inline-flex items-center justify-center gap-2 rounded-md bg-orange-500 px-4 py-2 text-sm font-bold text-neutral-950 hover:bg-orange-400 transition"
              >
                <RefreshCw className="h-4 w-4" />
                Thử lại
              </button>
              <button
                onClick={this.handleGoHome}
                className="inline-flex items-center justify-center gap-2 rounded-md border border-neutral-700 px-4 py-2 text-sm font-bold text-neutral-300 hover:text-white hover:border-neutral-500 transition"
              >
                <Home className="h-4 w-4" />
                Về trang chủ
              </button>
            </div>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
