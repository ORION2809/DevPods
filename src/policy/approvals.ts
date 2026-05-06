import { randomUUID } from 'node:crypto';

export function createActionId(): string {
  return `act_${randomUUID().replace(/-/g, '').slice(0, 12)}`;
}

export function isApprovalExpired(expiresAt: Date, now: Date = new Date()): boolean {
  return expiresAt.getTime() < now.getTime();
}