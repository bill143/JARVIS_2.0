/**
 * MODULE 6 — CONSTRUCTION VALIDATOR: Lien Waiver
 * Checks dates, notarization fields, amount consistency
 */

import { z } from 'zod';

export const LienWaiverData = z.object({
  type: z.enum(['conditional_progress', 'unconditional_progress', 'conditional_final', 'unconditional_final']),
  claimant: z.string(),
  project: z.string(),
  throughDate: z.string(),
  amount: z.number(),
  notarized: z.boolean(),
  notaryDate: z.string().nullable(),
  notaryName: z.string().nullable(),
  notaryExpiration: z.string().nullable(),
  checkNumber: z.string().nullable(),
  paymentAmount: z.number().nullable(),
});

export type LienWaiverDataType = z.infer<typeof LienWaiverData>;

export interface LienWaiverValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
}

export class LienWaiverValidator {
  validate(waiver: LienWaiverDataType, expectedAmount?: number): LienWaiverValidationResult {
    const errors: string[] = [];
    const warnings: string[] = [];

    // Amount consistency
    if (expectedAmount !== undefined && Math.abs(waiver.amount - expectedAmount) > 0.01) {
      errors.push(`Waiver amount ($${waiver.amount}) does not match expected ($${expectedAmount})`);
    }

    // Payment amount consistency
    if (waiver.paymentAmount !== null && Math.abs(waiver.amount - waiver.paymentAmount) > 0.01) {
      warnings.push(`Waiver amount ($${waiver.amount}) differs from payment amount ($${waiver.paymentAmount})`);
    }

    // Notarization required for unconditional waivers
    if (waiver.type.startsWith('unconditional') && !waiver.notarized) {
      errors.push('Unconditional waiver requires notarization');
    }

    // Notary expiration check
    if (waiver.notarized && waiver.notaryExpiration) {
      const expiry = new Date(waiver.notaryExpiration);
      const waiverDate = new Date(waiver.throughDate);
      if (expiry < waiverDate) {
        errors.push(`Notary commission expired (${waiver.notaryExpiration}) before waiver date (${waiver.throughDate})`);
      }
    }

    // Date validation
    const throughDate = new Date(waiver.throughDate);
    if (isNaN(throughDate.getTime())) {
      errors.push(`Invalid through date: ${waiver.throughDate}`);
    }
    if (throughDate > new Date()) {
      warnings.push('Through date is in the future');
    }

    // Required fields for final waivers
    if (waiver.type.includes('final') && !waiver.checkNumber) {
      warnings.push('Final waiver should reference a check/payment number');
    }

    return { valid: errors.length === 0, errors, warnings };
  }
}
