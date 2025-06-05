/**
 * Authentication middleware
 */
export function authMiddleware(apiKey) {
  return (req, res, next) => {
    // Skip auth if no API key is configured
    if (!apiKey) {
      return next();
    }

    // Check for Authorization header
    const authHeader = req.headers.authorization;
    
    if (!authHeader) {
      return res.status(401).json({
        error: 'Missing authorization header'
      });
    }

    // Check Bearer token format
    const parts = authHeader.split(' ');
    if (parts.length !== 2 || parts[0] !== 'Bearer') {
      return res.status(401).json({
        error: 'Invalid authorization format. Use: Bearer <token>'
      });
    }

    // Verify API key
    if (parts[1] !== apiKey) {
      return res.status(401).json({
        error: 'Invalid API key'
      });
    }

    next();
  };
}
