'use strict';

const crypto = require('node:crypto');

const API_VERSION = '2019-02-28';
const ENDPOINT = 'https://nls-meta.cn-shanghai.aliyuncs.com/';

function getEnv(name) {
  return (process.env[name] || '').trim();
}

function json(statusCode, payload, extraHeaders = {}) {
  return {
    statusCode,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      ...extraHeaders,
    },
    body: JSON.stringify(payload),
  };
}

function percentEncode(value) {
  return encodeURIComponent(value)
    .replace(/\+/g, '%20')
    .replace(/\*/g, '%2A')
    .replace(/%7E/g, '~');
}

function buildSignedUrl() {
  const accessKeyId = getEnv('ALIYUN_ACCESS_KEY_ID');
  const accessKeySecret = getEnv('ALIYUN_ACCESS_KEY_SECRET');
  const regionId = getEnv('ALIYUN_REGION_ID') || 'cn-shanghai';

  if (!accessKeyId || !accessKeySecret) {
    throw new Error('Missing ALIYUN_ACCESS_KEY_ID or ALIYUN_ACCESS_KEY_SECRET');
  }

  const params = {
    AccessKeyId: accessKeyId,
    Action: 'CreateToken',
    Format: 'JSON',
    RegionId: regionId,
    SignatureMethod: 'HMAC-SHA1',
    SignatureNonce: crypto.randomUUID(),
    SignatureVersion: '1.0',
    Timestamp: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    Version: API_VERSION,
  };

  const canonicalizedQuery = Object.keys(params)
    .sort()
    .map((key) => `${percentEncode(key)}=${percentEncode(params[key])}`)
    .join('&');

  const stringToSign = `GET&${percentEncode('/')}&${percentEncode(canonicalizedQuery)}`;
  const signature = crypto
    .createHmac('sha1', `${accessKeySecret}&`)
    .update(stringToSign)
    .digest('base64');

  const url = new URL(ENDPOINT);
  for (const [key, value] of Object.entries(params)) {
    url.searchParams.set(key, value);
  }
  url.searchParams.set('Signature', signature);
  return {
    url: url.toString(),
    regionId,
  };
}

async function requestAliyunToken() {
  const appKey = getEnv('ALIYUN_APP_KEY');
  if (!appKey) {
    throw new Error('Missing ALIYUN_APP_KEY');
  }

  const { url, regionId } = buildSignedUrl();
  const response = await fetch(url, {
    method: 'GET',
    headers: {
      Accept: 'application/json',
    },
  });

  const text = await response.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch (error) {
    throw new Error(`Invalid Aliyun response: ${text}`);
  }

  if (!response.ok) {
    const message = data.ErrMsg || data.Message || data.message || response.statusText;
    const code = data.ErrCode || data.Code || response.status;
    const requestId = data.RequestId || '';
    throw new Error(`Aliyun CreateToken failed: code=${code}, message=${message}, requestId=${requestId}`);
  }

  const errCode = Number(data.ErrCode || 0);
  if (errCode !== 0) {
    const message = data.ErrMsg || data.Message || data.message || 'Unknown error';
    const requestId = data.RequestId || '';
    throw new Error(`Aliyun CreateToken business error: code=${errCode}, message=${message}, requestId=${requestId}`);
  }

  const token = (data.Token && data.Token.Id) || '';
  const expireTime = Number((data.Token && data.Token.ExpireTime) || 0);
  if (!token) {
    const message = data.ErrMsg || data.Message || data.message || 'Token.Id missing';
    const requestId = data.RequestId || '';
    throw new Error(`Aliyun CreateToken response invalid: ${message}, requestId=${requestId}, raw=${text}`);
  }

  return {
    token,
    expire_time: expireTime,
    app_key: appKey,
    region_id: regionId,
  };
}

async function handleRequest(method) {
  if (method === 'OPTIONS') {
    return json(204, {});
  }

  if (method !== 'GET') {
    return json(405, { error: 'method_not_allowed' }, { Allow: 'GET, OPTIONS' });
  }

  try {
    const token = await requestAliyunToken();
    return json(200, token);
  } catch (error) {
    console.error('Aliyun token fetch failed:', error);
    return json(500, {
      error: 'token_fetch_failed',
      message: error.message || 'Unknown error',
    });
  }
}

exports.main_handler = async (event = {}) => {
  const method =
    event.httpMethod ||
    event.requestContext?.http?.method ||
    'GET';
  return handleRequest(method.toUpperCase());
};

if (require.main === module) {
  const http = require('node:http');

  const port = Number(process.env.PORT || 3000);
  const server = http.createServer(async (req, res) => {
    const result = await handleRequest((req.method || 'GET').toUpperCase());
    res.writeHead(result.statusCode, result.headers);
    res.end(result.body);
  });

  server.listen(port, () => {
    console.log(`Aliyun token server listening on http://127.0.0.1:${port}`);
  });
}
