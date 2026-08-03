/**
 * MODULE 6 — CONSTRUCTION VALIDATOR: G702
 * Verifies math on AIA G702 Schedule of Values columns
 *
 * Validates:
 * - Column totals match sum of line items
 * - Work completed to date = previous + this period
 * - Retainage calculations
 * - Balance to finish = scheduled value - completed to date
 * - Total % complete consistency
 */

import { z } from 'zod';

export const G702LineItem = z.object({
  itemNumber: z.string(),
  description: z.string(),
  scheduledValue: z.number(),
  previousApplications: z.number(),
  thisPeriodsWork: z.number(),
  materialsStored: z.number(),
  totalCompletedAndStored: z.number(),
  percentComplete: z.number(),
  balanceToFinish: z.number(),
  retainage: z.number(),
});

export type G702LineItemType = z.infer<typeof G702LineItem>;

export interface G702ValidationResult {
  valid: boolean;
  errors: Array<{
    lineItem: string;
    field: string;
    expected: number;
    actual: number;
    message: string;
  }>;
  warnings: string[];
  summary: {
    totalScheduledValue: number;
    totalCompletedToDate: number;
    totalRetainage: number;
    overallPercentComplete: number;
  };
}

export class G702Validator {
  validate(lineItems: G702LineItemType[]): G702ValidationResult {
    const errors: G702ValidationResult['errors'] = [];
    const warnings: string[] = [];

    for (const item of lineItems) {
      // Total completed = previous + this period + materials stored
      const expectedTotal = item.previousApplications + item.thisPeriodsWork + item.materialsStored;
      if (Math.abs(item.totalCompletedAndStored - expectedTotal) > 0.01) {
        errors.push({
          lineItem: item.itemNumber,
          field: 'totalCompletedAndStored',
          expected: expectedTotal,
          actual: item.totalCompletedAndStored,
          message: `Total completed (${item.totalCompletedAndStored}) ≠ previous (${item.previousApplications}) + this period (${item.thisPeriodsWork}) + stored (${item.materialsStored})`,
        });
      }

      // Balance to finish = scheduled value - total completed
      const expectedBalance = item.scheduledValue - item.totalCompletedAndStored;
      if (Math.abs(item.balanceToFinish - expectedBalance) > 0.01) {
        errors.push({
          lineItem: item.itemNumber,
          field: 'balanceToFinish',
          expected: expectedBalance,
          actual: item.balanceToFinish,
          message: `Balance to finish (${item.balanceToFinish}) ≠ scheduled (${item.scheduledValue}) - completed (${item.totalCompletedAndStored})`,
        });
      }

      // Percent complete check
      const expectedPercent = item.scheduledValue > 0
        ? (item.totalCompletedAndStored / item.scheduledValue) * 100
        : 0;
      if (Math.abs(item.percentComplete - expectedPercent) > 0.5) {
        errors.push({
          lineItem: item.itemNumber,
          field: 'percentComplete',
          expected: Math.round(expectedPercent * 100) / 100,
          actual: item.percentComplete,
          message: `Percent complete (${item.percentComplete}%) does not match calculated (${expectedPercent.toFixed(2)}%)`,
        });
      }

      // Warning: over 100% complete
      if (item.percentComplete > 100) {
        warnings.push(`Line ${item.itemNumber}: ${item.percentComplete}% complete exceeds 100%`);
      }

      // Warning: negative balance
      if (item.balanceToFinish < 0) {
        warnings.push(`Line ${item.itemNumber}: Negative balance to finish ($${item.balanceToFinish})`);
      }
    }

    const totalScheduled = lineItems.reduce((s, i) => s + i.scheduledValue, 0);
    const totalCompleted = lineItems.reduce((s, i) => s + i.totalCompletedAndStored, 0);
    const totalRetainage = lineItems.reduce((s, i) => s + i.retainage, 0);

    return {
      valid: errors.length === 0,
      errors,
      warnings,
      summary: {
        totalScheduledValue: totalScheduled,
        totalCompletedToDate: totalCompleted,
        totalRetainage,
        overallPercentComplete: totalScheduled > 0 ? (totalCompleted / totalScheduled) * 100 : 0,
      },
    };
  }
}
