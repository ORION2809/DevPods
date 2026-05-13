export type ErrorCategory = 'network' | 'auth' | 'timeout' | 'unknown';

export interface ClassifiedError {
  category: ErrorCategory;
  userMessage: string;
  retryable: boolean;
}

export function classifyError(error: unknown): ClassifiedError {
  if (!(error instanceof Error)) {
    return {
      category: 'unknown',
      userMessage: 'Something went wrong. Please try again.',
      retryable: false,
    };
  }

  const message = error.message.toLowerCase();

  if (
    message.includes('econnrefused') ||
    message.includes('enotfound') ||
    message.includes('network') ||
    message.includes('fetch failed') ||
    message.includes('eai_again')
  ) {
    return {
      category: 'network',
      userMessage: 'Network connection failed. Please check your connection and try again.',
      retryable: true,
    };
  }

  if (
    message.includes('etimedout') ||
    message.includes('timeout') ||
    message.includes('aborted')
  ) {
    return {
      category: 'timeout',
      userMessage: 'The request timed out. Please try again.',
      retryable: true,
    };
  }

  if (
    message.includes('unauthorized') ||
    message.includes('auth') ||
    message.includes('forbidden') ||
    message.includes('eacces')
  ) {
    return {
      category: 'auth',
      userMessage: 'Authentication failed. Please check your credentials.',
      retryable: false,
    };
  }

  return {
    category: 'unknown',
    userMessage: 'Something went wrong. Please try again.',
    retryable: false,
  };
}
