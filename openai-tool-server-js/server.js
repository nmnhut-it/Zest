import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import morgan from 'morgan';
import dotenv from 'dotenv';
import rateLimit from 'express-rate-limit';
import swaggerJsdoc from 'swagger-jsdoc';
import swaggerUi from 'swagger-ui-express';
import { ZestProxyClient } from './lib/proxy-client.js';
import { authMiddleware } from './middleware/auth.js';
import { errorHandler } from './middleware/error-handler.js';
import { validateRequest } from './middleware/validation.js';
import * as schemas from './schemas/index.js';

// Load environment variables
dotenv.config();

// Configuration
const PORT = process.env.PORT || 8000;
const API_KEY = process.env.OPENAI_TOOL_SERVER_API_KEY || '';
const PROXY_BASE_URL = process.env.ZEST_PROXY_URL || null;

// Create Express app
const app = express();

// Global proxy client
let proxyClient = null;

// Middleware
app.use(helmet());
app.use(cors());
app.use(express.json());
app.use(morgan('combined'));

// Rate limiting
const limiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutes
  max: 100 // limit each IP to 100 requests per windowMs
});
app.use('/api/', limiter);

// Swagger documentation
const swaggerOptions = {
  definition: {
    openapi: '3.0.0',
    info: {
      title: 'Zest Code Explorer - OpenAI Tool Server',
      version: '1.0.0',
      description: 'OpenAPI-compliant server for IntelliJ code exploration through Zest',
    },
    servers: [
      {
        url: `http://localhost:${PORT}`,
        description: 'Development server',
      },
    ],
    components: {
      securitySchemes: {
        bearerAuth: {
          type: 'http',
          scheme: 'bearer',
          bearerFormat: 'JWT',
        },
      },
    },
    security: API_KEY ? [{ bearerAuth: [] }] : [],
  },
  apis: ['./server.js', './routes/*.js'],
};

const swaggerSpec = swaggerJsdoc(swaggerOptions);

// Swagger UI
app.use('/docs', swaggerUi.serve, swaggerUi.setup(swaggerSpec));
app.get('/openapi.json', (req, res) => {
  // Add OpenAI extensions
  const spec = { ...swaggerSpec };
  
  // Add x-openai extensions to each endpoint
  Object.entries(spec.paths).forEach(([path, methods]) => {
    Object.entries(methods).forEach(([method, operation]) => {
      if (['post', 'get'].includes(method)) {
        operation['x-openai-tool'] = {
          type: 'function',
          function: {
            name: operation.operationId || path.replace(/\//g, '_').substring(1),
            description: operation.summary || '',
            parameters: operation.requestBody?.content?.['application/json']?.schema || {
              type: 'object',
              properties: {}
            }
          }
        };
      }
    });
  });
  
  res.json(spec);
});

// Initialize proxy client
async function initializeProxy() {
  proxyClient = new ZestProxyClient(PROXY_BASE_URL);
  const connected = await proxyClient.findProxy();
  
  if (connected) {
    console.log(`✓ Connected to Zest Agent Proxy on port ${proxyClient.proxyPort}`);
  } else {
    console.warn('✗ No Zest Agent Proxy found. Please start it from IntelliJ IDEA.');
  }
  
  return connected;
}

// Health check endpoint
/**
 * @swagger
 * /health:
 *   get:
 *     summary: Check server health and proxy connection
 *     tags: [System]
 *     responses:
 *       200:
 *         description: Server health status
 *         content:
 *           application/json:
 *             schema:
 *               type: object
 *               properties:
 *                 status:
 *                   type: string
 *                 proxy_connected:
 *                   type: boolean
 *                 proxy_info:
 *                   type: object
 */
app.get('/health', async (req, res) => {
  try {
    const proxyHealth = await proxyClient.health();
    res.json({
      status: 'healthy',
      proxy_connected: true,
      proxy_info: proxyHealth
    });
  } catch (error) {
    res.json({
      status: 'healthy',
      proxy_connected: false,
      proxy_info: null
    });
  }
});

// Core endpoints
/**
 * @swagger
 * /explore:
 *   post:
 *     summary: Explore code with a natural language query
 *     tags: [Exploration]
 *     operationId: exploreCode
 *     security:
 *       - bearerAuth: []
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             $ref: '#/components/schemas/ExploreCodeRequest'
 *     responses:
 *       200:
 *         description: Exploration results
 *         content:
 *           application/json:
 *             schema:
 *               $ref: '#/components/schemas/ExploreCodeResponse'
 */
app.post('/explore', 
  authMiddleware(API_KEY),
  validateRequest(schemas.exploreCodeSchema),
  async (req, res, next) => {
    try {
      const result = await proxyClient.explore(req.body);
      res.json(result);
    } catch (error) {
      next(error);
    }
  }
);

/**
 * @swagger
 * /execute-tool:
 *   post:
 *     summary: Execute a specific exploration tool
 *     tags: [Tools]
 *     operationId: executeTool
 *     security:
 *       - bearerAuth: []
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             $ref: '#/components/schemas/ExecuteToolRequest'
 *     responses:
 *       200:
 *         description: Tool execution result
 */
app.post('/execute-tool',
  authMiddleware(API_KEY),
  validateRequest(schemas.executeToolSchema),
  async (req, res, next) => {
    try {
      const result = await proxyClient.executeTool(req.body);
      res.json(result);
    } catch (error) {
      next(error);
    }
  }
);

/**
 * @swagger
 * /tools:
 *   get:
 *     summary: List all available exploration tools
 *     tags: [Tools]
 *     operationId: listTools
 *     security:
 *       - bearerAuth: []
 *     responses:
 *       200:
 *         description: List of available tools
 */
app.get('/tools',
  authMiddleware(API_KEY),
  async (req, res, next) => {
    try {
      const tools = await proxyClient.listTools();
      res.json(tools);
    } catch (error) {
      next(error);
    }
  }
);

// Convenience endpoints
/**
 * @swagger
 * /search:
 *   post:
 *     summary: Search for code using natural language
 *     tags: [Search]
 *     operationId: searchCode
 *     security:
 *       - bearerAuth: []
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             $ref: '#/components/schemas/SearchCodeRequest'
 *     responses:
 *       200:
 *         description: Search results
 */
app.post('/search',
  authMiddleware(API_KEY),
  validateRequest(schemas.searchCodeSchema),
  async (req, res, next) => {
    try {
      const result = await proxyClient.executeTool({
        tool: 'search_code',
        parameters: req.body
      });
      res.json(result);
    } catch (error) {
      next(error);
    }
  }
);

/**
 * @swagger
 * /find-by-name:
 *   post:
 *     summary: Find code elements by name
 *     tags: [Search]
 *     operationId: findByName
 *     security:
 *       - bearerAuth: []
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             $ref: '#/components/schemas/FindByNameRequest'
 *     responses:
 *       200:
 *         description: Found elements
 */
app.post('/find-by-name',
  authMiddleware(API_KEY),
  validateRequest(schemas.findByNameSchema),
  async (req, res, next) => {
    try {
      const result = await proxyClient.executeTool({
        tool: 'find_by_name',
        parameters: req.body
      });
      res.json(result);
    } catch (error) {
      next(error);
    }
  }
);

/**
 * @swagger
 * /read-file:
 *   post:
 *     summary: Read a file from the project
 *     tags: [Files]
 *     operationId: readFile
 *     security:
 *       - bearerAuth: []
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             $ref: '#/components/schemas/ReadFileRequest'
 *     responses:
 *       200:
 *         description: File contents
 */
app.post('/read-file',
  authMiddleware(API_KEY),
  validateRequest(schemas.readFileSchema),
  async (req, res, next) => {
    try {
      const result = await proxyClient.executeTool({
        tool: 'read_file',
        parameters: req.body
      });
      res.json(result);
    } catch (error) {
      next(error);
    }
  }
);

/**
 * @swagger
 * /find-relationships:
 *   post:
 *     summary: Find relationships between code elements
 *     tags: [Analysis]
 *     operationId: findRelationships
 *     security:
 *       - bearerAuth: []
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             $ref: '#/components/schemas/FindRelationshipsRequest'
 *     responses:
 *       200:
 *         description: Found relationships
 */
app.post('/find-relationships',
  authMiddleware(API_KEY),
  validateRequest(schemas.findRelationshipsSchema),
  async (req, res, next) => {
    try {
      const result = await proxyClient.executeTool({
        tool: 'find_relationships',
        parameters: req.body
      });
      res.json(result);
    } catch (error) {
      next(error);
    }
  }
);

/**
 * @swagger
 * /find-usages:
 *   post:
 *     summary: Find usages of a code element
 *     tags: [Analysis]
 *     operationId: findUsages
 *     security:
 *       - bearerAuth: []
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             $ref: '#/components/schemas/FindUsagesRequest'
 *     responses:
 *       200:
 *         description: Found usages
 */
app.post('/find-usages',
  authMiddleware(API_KEY),
  validateRequest(schemas.findUsagesSchema),
  async (req, res, next) => {
    try {
      const result = await proxyClient.executeTool({
        tool: 'find_usages',
        parameters: req.body
      });
      res.json(result);
    } catch (error) {
      next(error);
    }
  }
);

/**
 * @swagger
 * /class-info:
 *   post:
 *     summary: Get detailed information about a class
 *     tags: [Analysis]
 *     operationId: getClassInfo
 *     security:
 *       - bearerAuth: []
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             $ref: '#/components/schemas/GetClassInfoRequest'
 *     responses:
 *       200:
 *         description: Class information
 */
app.post('/class-info',
  authMiddleware(API_KEY),
  validateRequest(schemas.getClassInfoSchema),
  async (req, res, next) => {
    try {
      const result = await proxyClient.executeTool({
        tool: 'get_class_info',
        parameters: req.body
      });
      res.json(result);
    } catch (error) {
      next(error);
    }
  }
);

/**
 * @swagger
 * /augment:
 *   post:
 *     summary: Augment a query with code context
 *     tags: [Augmentation]
 *     operationId: augmentQuery
 *     security:
 *       - bearerAuth: []
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             $ref: '#/components/schemas/AugmentQueryRequest'
 *     responses:
 *       200:
 *         description: Augmented query
 */
app.post('/augment',
  authMiddleware(API_KEY),
  validateRequest(schemas.augmentQuerySchema),
  async (req, res, next) => {
    try {
      const result = await proxyClient.augment(req.body);
      res.json(result);
    } catch (error) {
      next(error);
    }
  }
);

/**
 * @swagger
 * /status:
 *   get:
 *     summary: Get current proxy status
 *     tags: [System]
 *     operationId: getStatus
 *     security:
 *       - bearerAuth: []
 *     responses:
 *       200:
 *         description: Proxy status
 */
app.get('/status',
  authMiddleware(API_KEY),
  async (req, res, next) => {
    try {
      const status = await proxyClient.status();
      res.json(status);
    } catch (error) {
      next(error);
    }
  }
);

/**
 * @swagger
 * /config:
 *   get:
 *     summary: Get current proxy configuration
 *     tags: [System]
 *     operationId: getConfig
 *     security:
 *       - bearerAuth: []
 *     responses:
 *       200:
 *         description: Current configuration
 */
app.get('/config',
  authMiddleware(API_KEY),
  async (req, res, next) => {
    try {
      const config = await proxyClient.getConfig();
      res.json(config);
    } catch (error) {
      next(error);
    }
  }
);

/**
 * @swagger
 * /config:
 *   post:
 *     summary: Update proxy configuration
 *     tags: [System]
 *     operationId: updateConfig
 *     security:
 *       - bearerAuth: []
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             $ref: '#/components/schemas/ConfigUpdateRequest'
 *     responses:
 *       200:
 *         description: Updated configuration
 */
app.post('/config',
  authMiddleware(API_KEY),
  validateRequest(schemas.configUpdateSchema),
  async (req, res, next) => {
    try {
      const config = await proxyClient.updateConfig(req.body);
      res.json(config);
    } catch (error) {
      next(error);
    }
  }
);

// Error handling
app.use(errorHandler);

// Start server
async function startServer() {
  console.log('🚀 Starting Zest OpenAI Tool Server...');
  
  // Initialize proxy connection
  await initializeProxy();
  
  // Start listening
  app.listen(PORT, () => {
    console.log(`✓ Server running on http://localhost:${PORT}`);
    console.log(`📚 API documentation: http://localhost:${PORT}/docs`);
    console.log(`📄 OpenAPI schema: http://localhost:${PORT}/openapi.json`);
    
    if (API_KEY) {
      console.log('🔒 API key authentication enabled');
    } else {
      console.log('⚠️  Warning: API key authentication disabled');
    }
  });
}

// Handle graceful shutdown
process.on('SIGTERM', () => {
  console.log('SIGTERM received, shutting down gracefully...');
  process.exit(0);
});

process.on('SIGINT', () => {
  console.log('SIGINT received, shutting down gracefully...');
  process.exit(0);
});

// Start the server
startServer().catch(console.error);
