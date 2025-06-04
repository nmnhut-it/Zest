import Joi from 'joi';

// Explore code schema
export const exploreCodeSchema = Joi.object({
  query: Joi.string().required().description('Natural language query about the code'),
  generate_report: Joi.boolean().default(false).description('Generate detailed report'),
  config: Joi.object({
    maxToolCalls: Joi.number().integer().min(1),
    includeTests: Joi.boolean(),
    deepExploration: Joi.boolean()
  }).optional().description('Configuration overrides')
});

// Execute tool schema
export const executeToolSchema = Joi.object({
  tool: Joi.string().required().description('Name of the tool to execute'),
  parameters: Joi.object().required().description('Tool-specific parameters')
});

// Search code schema
export const searchCodeSchema = Joi.object({
  query: Joi.string().required().description('Natural language search query'),
  max_results: Joi.number().integer().min(1).max(100).default(10)
    .description('Maximum results')
});

// Find by name schema
export const findByNameSchema = Joi.object({
  name: Joi.string().required().description('Class, method, or package name (case-sensitive)'),
  type: Joi.string().valid('class', 'method', 'package', 'any').default('any')
    .description('Type of element to find')
});

// Read file schema
export const readFileSchema = Joi.object({
  file_path: Joi.string().required().description('Path to the file to read')
});

// Find relationships schema
export const findRelationshipsSchema = Joi.object({
  element_id: Joi.string().required().description('Fully qualified class name'),
  relation_type: Joi.string().valid(
    'EXTENDS', 'IMPLEMENTS', 'USES', 'USED_BY',
    'CALLS', 'CALLED_BY', 'OVERRIDES', 'OVERRIDDEN_BY'
  ).optional().description('Type of relationship to find')
});

// Find usages schema
export const findUsagesSchema = Joi.object({
  element_id: Joi.string().required().description('Class or method to find usages of')
});

// Get class info schema
export const getClassInfoSchema = Joi.object({
  class_name: Joi.string().required().description('Fully qualified class name')
});

// Augment query schema
export const augmentQuerySchema = Joi.object({
  query: Joi.string().required().description('User query to augment with code context')
});

// Config update schema
export const configUpdateSchema = Joi.object({
  max_tool_calls: Joi.number().integer().min(1).optional(),
  max_rounds: Joi.number().integer().min(1).optional(),
  include_tests: Joi.boolean().optional(),
  deep_exploration: Joi.boolean().optional(),
  timeout_seconds: Joi.number().integer().min(1).optional()
});

// Schema definitions for Swagger
export const swaggerSchemas = {
  ExploreCodeRequest: {
    type: 'object',
    required: ['query'],
    properties: {
      query: {
        type: 'string',
        description: 'Natural language query about the code'
      },
      generate_report: {
        type: 'boolean',
        default: false,
        description: 'Generate detailed report'
      },
      config: {
        type: 'object',
        properties: {
          maxToolCalls: { type: 'integer', minimum: 1 },
          includeTests: { type: 'boolean' },
          deepExploration: { type: 'boolean' }
        }
      }
    }
  },
  ExploreCodeResponse: {
    type: 'object',
    properties: {
      success: { type: 'boolean' },
      summary: { type: 'string' },
      report: { type: 'object' },
      error: { type: 'string' }
    }
  },
  ExecuteToolRequest: {
    type: 'object',
    required: ['tool', 'parameters'],
    properties: {
      tool: {
        type: 'string',
        description: 'Name of the tool to execute'
      },
      parameters: {
        type: 'object',
        description: 'Tool-specific parameters'
      }
    }
  },
  SearchCodeRequest: {
    type: 'object',
    required: ['query'],
    properties: {
      query: {
        type: 'string',
        description: 'Natural language search query'
      },
      max_results: {
        type: 'integer',
        default: 10,
        minimum: 1,
        maximum: 100
      }
    }
  },
  FindByNameRequest: {
    type: 'object',
    required: ['name'],
    properties: {
      name: {
        type: 'string',
        description: 'Class, method, or package name (case-sensitive)'
      },
      type: {
        type: 'string',
        enum: ['class', 'method', 'package', 'any'],
        default: 'any'
      }
    }
  },
  ReadFileRequest: {
    type: 'object',
    required: ['file_path'],
    properties: {
      file_path: {
        type: 'string',
        description: 'Path to the file to read'
      }
    }
  },
  FindRelationshipsRequest: {
    type: 'object',
    required: ['element_id'],
    properties: {
      element_id: {
        type: 'string',
        description: 'Fully qualified class name'
      },
      relation_type: {
        type: 'string',
        enum: ['EXTENDS', 'IMPLEMENTS', 'USES', 'USED_BY', 'CALLS', 'CALLED_BY', 'OVERRIDES', 'OVERRIDDEN_BY']
      }
    }
  },
  FindUsagesRequest: {
    type: 'object',
    required: ['element_id'],
    properties: {
      element_id: {
        type: 'string',
        description: 'Class or method to find usages of'
      }
    }
  },
  GetClassInfoRequest: {
    type: 'object',
    required: ['class_name'],
    properties: {
      class_name: {
        type: 'string',
        description: 'Fully qualified class name'
      }
    }
  },
  AugmentQueryRequest: {
    type: 'object',
    required: ['query'],
    properties: {
      query: {
        type: 'string',
        description: 'User query to augment with code context'
      }
    }
  },
  ConfigUpdateRequest: {
    type: 'object',
    properties: {
      max_tool_calls: { type: 'integer', minimum: 1 },
      max_rounds: { type: 'integer', minimum: 1 },
      include_tests: { type: 'boolean' },
      deep_exploration: { type: 'boolean' },
      timeout_seconds: { type: 'integer', minimum: 1 }
    }
  }
};
