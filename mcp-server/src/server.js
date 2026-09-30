import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { registerTools } from './tools.js';

/**
 * 建一个注册好 4 个工具的 McpServer。index.js（stdio 启动）与测试（InMemoryTransport）共用，
 * 保证测的就是真启动的那个 server。
 */
export function createServer(fs) {
  const server = new McpServer({ name: 'datamodelrobot', version: '0.1.0' });
  registerTools(server, fs);
  return server;
}
