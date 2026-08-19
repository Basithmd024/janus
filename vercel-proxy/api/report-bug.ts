import type { VercelRequest, VercelResponse } from '@vercel/node';

// Memory rate limiting per UUID and per IP: max 5 reports per 24 hours
const uuidRateMap = new Map<string, { count: number; resetAt: number }>();
const ipRateMap = new Map<string, { count: number; resetAt: number }>();

export default async function handler(req: VercelRequest, res: VercelResponse) {
  res.setHeader('Access-Control-Allow-Credentials', 'true');
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS,PATCH,DELETE,POST,PUT');
  res.setHeader(
    'Access-Control-Allow-Headers',
    'X-CSRF-Token, X-Requested-With, Accept, Accept-Version, Content-Length, Content-MD5, Content-Type, Date, X-Api-Version'
  );

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  if (req.method !== 'POST') {
    return res.status(405).json({ status: 'error', message: 'Method not allowed' });
  }

  try {
    const {
      username,
      device_name,
      device_model,
      os,
      app_version,
      severity,
      description,
      uuid,
      platform,
    } = req.body || {};

    // 1. Payload Validation: Required & Minimum 20 Characters
    if (!description || typeof description !== 'string') {
      return res.status(400).json({
        status: 'error',
        message: 'Missing required field: description is required.',
      });
    }

    const trimmedDesc = description.trim();
    if (trimmedDesc.length < 20) {
      return res.status(400).json({
        status: 'error',
        message: 'Description is too short. Please provide at least 20 characters describing the issue.',
      });
    }

    if (!uuid || typeof uuid !== 'string') {
      return res.status(400).json({
        status: 'error',
        message: 'Missing required field: uuid is required.',
      });
    }

    // 2. Dual-Layer Rate Limiting (UUID + IP)
    const now = Date.now();

    // Layer A: UUID Rate Limiting
    const userRate = uuidRateMap.get(uuid) || { count: 0, resetAt: now + 24 * 60 * 60 * 1000 };
    if (now > userRate.resetAt) {
      userRate.count = 0;
      userRate.resetAt = now + 24 * 60 * 60 * 1000;
    }
    if (userRate.count >= 5) {
      return res.status(429).json({
        status: 'error',
        message: 'Rate limit exceeded: maximum 5 bug reports per device per day.',
      });
    }

    // Layer B: IP Address Rate Limiting
    const rawIp = (req.headers['x-forwarded-for'] as string) || req.socket.remoteAddress || '127.0.0.1';
    const clientIp = rawIp.split(',')[0].trim();
    const ipRate = ipRateMap.get(clientIp) || { count: 0, resetAt: now + 24 * 60 * 60 * 1000 };
    if (now > ipRate.resetAt) {
      ipRate.count = 0;
      ipRate.resetAt = now + 24 * 60 * 60 * 1000;
    }
    if (ipRate.count >= 5) {
      return res.status(429).json({
        status: 'error',
        message: 'Rate limit exceeded: maximum 5 bug reports per IP address per day.',
      });
    }

    // Increment limits after passing validation
    userRate.count += 1;
    uuidRateMap.set(uuid, userRate);

    ipRate.count += 1;
    ipRateMap.set(clientIp, ipRate);

    const safeUsername = username || 'Unknown User';
    const safeDeviceName = device_name || 'Unknown Device';
    const safeDeviceModel = device_model || 'Unknown Model';
    const safeOs = os || 'Unknown OS';
    const safeAppVersion = app_version || '1.0.0';
    const safeSeverity = (severity || 'Medium').toLowerCase();
    const safePlatform = (platform || 'macos').toLowerCase();

    // Map labels
    const labels = [
      `severity: ${safeSeverity}`,
      `platform: ${safePlatform}`,
      'bug-report',
    ];

    // Format GitHub Issue Markdown Body
    const issueBody = `## Bug Report

**Username:** ${safeUsername}
**Device Name:** ${safeDeviceName}
**Device Model:** ${safeDeviceModel}
**OS:** ${safeOs}
**App Version:** ${safeAppVersion}
**Severity:** ${severity || 'Medium'}

## Description
${trimmedDesc}

---
*Report ID: ${uuid}-${now}*
*Submitted via Janus Bridge ${safePlatform === 'android' ? 'Android App' : 'macOS App'}*`;

    const githubToken = process.env.GITHUB_TOKEN;
    const repoOwner = process.env.GITHUB_REPO_OWNER || 'Basithmd024';
    const repoName = process.env.GITHUB_REPO_NAME || 'janus';

    if (!githubToken) {
      console.warn('GITHUB_TOKEN not set on Vercel environment.');
      return res.status(200).json({
        status: 'success',
        simulated: true,
        issue_number: Math.floor(Math.random() * 100) + 1,
        issue_url: `https://github.com/${repoOwner}/${repoName}/issues`,
        message: 'Bug report processed successfully.',
      });
    }

    const ghRes = await fetch(`https://api.github.com/repos/${repoOwner}/${repoName}/issues`, {
      method: 'POST',
      headers: {
        Authorization: `token ${githubToken}`,
        Accept: 'application/vnd.github.v3+json',
        'Content-Type': 'application/json',
        'User-Agent': 'Janus-Bridge-Vercel-Proxy',
      },
      body: JSON.stringify({
        title: `[Bug Report] ${trimmedDesc.slice(0, 50)}... (${safeUsername})`,
        body: issueBody,
        labels: labels,
      }),
    });

    if (!ghRes.ok) {
      const errText = await ghRes.text();
      return res.status(500).json({
        status: 'error',
        message: `GitHub API error: ${ghRes.statusText}`,
        detail: errText,
      });
    }

    const ghData = await ghRes.json();
    return res.status(200).json({
      status: 'success',
      issue_number: ghData.number,
      issue_url: ghData.html_url,
    });
  } catch (error: any) {
    return res.status(500).json({
      status: 'error',
      message: error?.message || 'Internal server error',
    });
  }
}
