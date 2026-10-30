import { createApp } from './app.js';
import { env } from './env.js';
import { logger } from './lib/logger.js';

const app = createApp();

app.listen(env.PORT, () => {
  logger.info('server_started', {
    port: env.PORT,
    nodeEnv: env.NODE_ENV,
    frontendOrigin: env.FRONTEND_ORIGIN
  });
});
