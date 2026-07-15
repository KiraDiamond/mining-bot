const net = require('net');
const { EventEmitter } = require('events');

const MAX_CONTROL_LINE_CHARS = 64 * 1024;

class TaskControlServer extends EventEmitter {
  constructor({ host = '127.0.0.1', port = 47391, token }) {
    super();
    this.host = host;
    this.port = port;
    this.token = token || 'dev-task-token';
    this.server = null;
    this.clients = new Map();
  }

  async start() {
    if (this.server) return;
    this.server = net.createServer((socket) => this.#handleSocket(socket));
    await new Promise((resolve, reject) => {
      this.server.once('error', reject);
      this.server.listen(this.port, this.host, () => {
        this.server.off('error', reject);
        resolve();
      });
    });
  }

  async stop() {
    for (const client of this.clients.values()) client.socket.destroy();
    this.clients.clear();
    if (!this.server) return;
    await new Promise((resolve) => this.server.close(resolve));
    this.server = null;
  }

  listClients() {
    return [...this.clients.entries()].map(([botId, client]) => ({
      botId,
      username: client.username || botId,
      status: client.status || null,
      connectedAt: client.connectedAt
    }));
  }

  getClient(botId) {
    return this.clients.get(botId);
  }

  send(botId, message) {
    const client = this.clients.get(botId);
    if (!client) throw new Error(`Bot "${botId}" is not connected.`);
    client.socket.write(`${JSON.stringify(message)}\n`);
  }

  broadcast(botIds, messageFactory) {
    for (const botId of botIds) {
      const message = typeof messageFactory === 'function' ? messageFactory(botId) : messageFactory;
      this.send(botId, message);
    }
  }

  #handleSocket(socket) {
    socket.setEncoding('utf8');
    socket.setKeepAlive(true, 30000);

    let buffer = '';
    let botId = null;

    const disconnect = () => {
      if (botId && this.clients.get(botId)?.socket === socket) {
        const client = this.clients.get(botId);
        this.clients.delete(botId);
        this.emit('disconnect', { botId, username: client?.username || botId });
      }
    };

    socket.on('data', (chunk) => {
      if (chunk.length > MAX_CONTROL_LINE_CHARS || buffer.length + chunk.length > MAX_CONTROL_LINE_CHARS) {
        socket.write(`${JSON.stringify({ type: 'error', error: 'Control message too large.' })}\n`);
        socket.destroy();
        return;
      }
      buffer += chunk;
      for (;;) {
        const index = buffer.indexOf('\n');
        if (index < 0) break;
        const line = buffer.slice(0, index).trim();
        buffer = buffer.slice(index + 1);
        if (!line) continue;

        let message;
        try {
          message = JSON.parse(line);
        } catch (error) {
          socket.write(`${JSON.stringify({ type: 'error', error: `Invalid JSON: ${error.message}` })}\n`);
          continue;
        }

        if (!botId) {
          if (message.type !== 'hello' || message.token !== this.token || !message.botId) {
            socket.write(`${JSON.stringify({ type: 'error', error: 'Authentication failed.' })}\n`);
            socket.destroy();
            return;
          }
          botId = String(message.botId).toLowerCase();
          const previous = this.clients.get(botId);
          if (previous) previous.socket.destroy();
          const client = {
            socket,
            botId,
            username: message.username || botId,
            connectedAt: new Date(),
            status: null
          };
          this.clients.set(botId, client);
          socket.write(`${JSON.stringify({ type: 'hello_ack', botId })}\n`);
          this.emit('connect', { botId, username: client.username });
          return;
        }

        const client = this.clients.get(botId);
        if (!client) return;
        if (message.type === 'status') client.status = message;
        if (message.username) client.username = message.username;
        this.emit('message', { botId, username: client.username || botId, message });
      }
    });

    socket.on('error', (error) => {
      this.emit('socketError', { botId, error });
    });
    socket.on('close', disconnect);
  }
}

module.exports = { TaskControlServer };
