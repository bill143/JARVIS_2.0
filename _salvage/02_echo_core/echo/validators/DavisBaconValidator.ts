/**
 * MODULE 6 — CONSTRUCTION VALIDATOR: Davis-Bacon
 * Confirms wage rates against published Wage Decision tables
 */

import { z } from 'zod';

export const LaborClassification = z.object({
  classification: z.string(),          // e.g., "Electrician", "Plumber"
  actualRate: z.number(),              // Hourly rate being paid
  actualFringe: z.number(),            // Fringe benefit rate being paid
  wageDecisionRate: z.number(),        // Published WD minimum rate
  wageDecisionFringe: z.number(),      // Published WD minimum fringe
  wageDecisionNumber: z.string(),      // e.g., "IL20250001"
  county: z.string(),
  projectType: z.enum(['building', 'heavy', 'highway', 'residential']),
});

export type LaborClassificationType = z.infer<typeof LaborClassification>;

export interface DavisBaconValidationResult {
  compliant: boolean;
  totalClassifications: number;
  compliantCount: number;
  violations: Array<{
    classification: string;
    rateShortfall: number;
    fringeShortfall: number;
    totalShortfall: number;
    wageDecision: string;
  }>;
  warnings: string[];
}

export class DavisBaconValidator {
  validate(classifications: LaborClassificationType[]): DavisBaconValidationResult {
    const violations: DavisBaconValidationResult['violations'] = [];
    const warnings: string[] = [];

    for (const cls of classifications) {
      const totalActual = cls.actualRate + cls.actualFringe;
      const totalRequired = cls.wageDecisionRate + cls.wageDecisionFringe;

      // Davis-Bacon allows fringe to be paid as cash in lieu
      // So total compensation (rate + fringe) must meet or exceed WD total
      if (totalActual < totalRequired) {
        const rateShortfall = Math.max(0, cls.wageDecisionRate - cls.actualRate);
        const fringeShortfall = Math.max(0, cls.wageDecisionFringe - cls.actualFringe);

        violations.push({
          classification: cls.classification,
          rateShortfall,
          fringeShortfall,
          totalShortfall: totalRequired - totalActual,
          wageDecision: cls.wageDecisionNumber,
        });
      }

      // Warning: rate exactly at minimum (no buffer)
      if (totalActual >= totalRequired && totalActual < totalRequired * 1.02) {
        warnings.push(`${cls.classification}: rate within 2% of WD minimum — consider buffer`);
      }
    }

    return {
      compliant: violations.length === 0,
      totalClassifications: classifications.length,
      compliantCount: classifications.length - violations.length,
      violations,
      warnings,
    };
  }
}
