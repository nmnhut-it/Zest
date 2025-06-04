/**
 * Global error handler middleware
 */
export function errorHandler(err, req, res, next) {
  console.error('Error:', err);

  // Handle validation errors
  if (err.name === 'ValidationError') {
    return res.status(400).json({
      error: 'Validation error',
      details: err.details
    });
  }

  // Handle proxy connection errors
  if (err.message && err.message.includes('Not connected to Zest Agent Proxy')) {
    return res.status(503).json({
      error: 'Service unavailable',
      message: 'Not connected to Zest Agent Proxy. Please start it from IntelliJ IDEA.'
    });
  }

  // Handle timeout errors
  if (err.message && err.message.includes('Request timeout')) {
    return res.status(504).json({
      error: 'Gateway timeout',
      message: err.message
    });
  }

  // Default error response
  res.status(err.status || 500).json({
    error: err.message || 'Internal server error',
    ...(process.env.NODE_ENV === 'development' && { stack: err.stack })
  });
}
