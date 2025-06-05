# Zest OpenAI Tool Server (JavaScript)

A Node.js/Express implementation of an OpenAPI-compliant server that exposes Zest code exploration capabilities as RESTful APIs, compatible with OpenAI function calling and other AI tools.

## Features

- **OpenAPI 3.0 Compliant**: Full OpenAPI specification with Swagger UI
- **Express.js Based**: Fast, minimalist web framework
- **Auto-discovery**: Automatically finds and connects to Zest Agent Proxy
- **Authentication**: Optional Bearer token authentication
- **Rate Limiting**: Built-in request rate limiting
- **Security**: Helmet.js for security headers, CORS support
- **Validation**: Joi-based request validation
- **Documentation**: Auto-generated Swagger documentation
- **OpenAI Compatible**: Includes x-openai extensions for function calling

## Quick Start

1. **Install dependencies:**
```bash
npm install
```

2. **Configure environment (optional):**
```bash
cp .env.example .env
# Edit .env file as needed
```

3. **Start the Zest Agent Proxy** in IntelliJ IDEA

4. **Run the server:**
```bash
# Development mode with auto-reload
npm run dev

# Production mode
npm start
```

5. **Access the API:**
- Interactive docs: http://localhost:8000/docs
- OpenAPI schema: http://localhost:8000/openapi.json
- Health check: http://localhost:8000/health

## API Endpoints

### Core Operations

- `POST /explore` - Explore code with natural language queries
- `POST /execute-tool` - Execute specific exploration tools
- `GET /tools` - List available tools

### Convenience Endpoints

- `POST /search` - Search code using natural language
- `POST /find-by-name` - Find elements by name
- `POST /read-file` - Read file contents
- `POST /find-relationships` - Find code relationships
- `POST /find-usages` - Find element usages
- `POST /class-info` - Get class information
- `POST /augment` - Augment queries with context

### System Endpoints

- `GET /health` - Server and proxy health check
- `GET /status` - Proxy connection status
- `GET /config` - Get configuration
- `POST /config` - Update configuration

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | Server port | `8000` |
| `NODE_ENV` | Environment (development/production) | `development` |
| `ZEST_PROXY_URL` | Zest Agent Proxy URL | Auto-discovery |
| `OPENAI_TOOL_SERVER_API_KEY` | API key for authentication | None (disabled) |
| `LOG_LEVEL` | Logging level | `info` |
| `RATE_LIMIT_WINDOW_MS` | Rate limit window (ms) | `900000` (15 min) |
| `RATE_LIMIT_MAX_REQUESTS` | Max requests per window | `100` |

## Example Usage

### Using fetch (Node.js)

```javascript
import fetch from 'node-fetch';

const API_URL = 'http://localhost:8000';
const API_KEY = 'your-api-key'; // Optional

// Search for code
const response = await fetch(`${API_URL}/search`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${API_KEY}`
  },
  body: JSON.stringify({
    query: 'find all REST endpoints',
    max_results: 20
  })
});

const result = await response.json();
console.log(result);
```

### Using axios

```javascript
import axios from 'axios';

const client = axios.create({
  baseURL: 'http://localhost:8000',
  headers: {
    'Authorization': 'Bearer your-api-key'
  }
});

// Explore code
const { data } = await client.post('/explore', {
  query: 'How does the authentication system work?',
  generate_report: true
});

console.log(data.summary);
```

### Integration with OpenAI

```javascript
import OpenAI from 'openai';

const openai = new OpenAI();

// Define the function
const functions = [{
  type: 'function',
  function: {
    name: 'explore_code',
    description: 'Explore code using natural language',
    parameters: {
      type: 'object',
      properties: {
        query: {
          type: 'string',
          description: 'Natural language query about the code'
        }
      },
      required: ['query']
    }
  }
}];

// Use in conversation
const response = await openai.chat.completions.create({
  model: 'gpt-4',
  messages: [
    { role: 'user', content: 'How does user authentication work in this codebase?' }
  ],
  functions,
  function_call: 'auto'
});

// Handle function calls
if (response.choices[0].message.function_call) {
  const functionCall = response.choices[0].message.function_call;
  const args = JSON.parse(functionCall.arguments);
  
  // Call your server
  const toolResponse = await fetch('http://localhost:8000/explore', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(args)
  });
  
  const result = await toolResponse.json();
  console.log(result.summary);
}
```

## Docker Support

```dockerfile
FROM node:18-slim

WORKDIR /app

COPY package*.json ./
RUN npm ci --only=production

COPY . .

EXPOSE 8000

CMD ["node", "server.js"]
```

Build and run:
```bash
docker build -t zest-openai-server-js .
docker run -p 8000:8000 -e OPENAI_TOOL_SERVER_API_KEY=secret zest-openai-server-js
```

## Testing

Run the test suite:
```bash
npm test
```

## Development

### Project Structure

```
openai-tool-server-js/
├── server.js           # Main server file
├── lib/
│   └── proxy-client.js # Zest proxy client
├── middleware/
│   ├── auth.js        # Authentication middleware
│   ├── error-handler.js # Error handling
│   └── validation.js   # Request validation
├── schemas/
│   └── index.js       # Joi validation schemas
├── examples/          # Usage examples
├── test/             # Test files
└── package.json
```

### Adding New Endpoints

1. Define the schema in `schemas/index.js`
2. Add the route in `server.js`
3. Add Swagger documentation
4. Update README with the new endpoint

## Security Best Practices

1. **Enable API key authentication in production**
2. **Use HTTPS** when exposing to the internet
3. **Configure CORS** appropriately for your use case
4. **Adjust rate limits** based on your needs
5. **Run behind a reverse proxy** (nginx, Apache) for additional security
6. **Keep dependencies updated** with `npm audit`

## License

MIT
