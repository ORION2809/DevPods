const authorizationBearerPattern = /(authorization\s*:\s*bearer\s+)([^\s,;]+)/gi;
const genericSecretAssignmentPattern = /(["']?(?:token|secret|password|api[-_]?key)["']?\s*[=:]\s*["']?)([^\s"',}]+)(["']?)/gi;
const githubTokenPattern = /ghp_[A-Za-z0-9]{20,}/g;
const googleApiKeyPattern = /AIza[0-9A-Za-z\-_]{20,}/g;

export function redactText(input: string | null | undefined): string | null {
  if (!input) {
    return null;
  }

  let output = input;
  output = output.replace(authorizationBearerPattern, (_match, prefix) => `${prefix}[REDACTED]`);
  output = output.replace(genericSecretAssignmentPattern, (_match, prefix, _value, suffix) => `${prefix}[REDACTED]${suffix}`);
  output = output.replace(githubTokenPattern, '[REDACTED]');
  output = output.replace(googleApiKeyPattern, '[REDACTED]');

  return output;
}