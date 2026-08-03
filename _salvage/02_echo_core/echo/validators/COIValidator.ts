/**
 * MODULE 6 — CONSTRUCTION VALIDATOR: Certificate of Insurance (COI)
 * Parses coverage limits, expiration dates, named insured
 */

import { z } from 'zod';

export const COIData = z.object({
  insured: z.string(),
  producer: z.string(),
  policyNumber: z.string(),
  effectiveDate: z.string(),
  expirationDate: z.string(),
  generalLiability: z.object({
    eachOccurrence: z.number(),
    generalAggregate: z.number(),
    productsCompOps: z.number(),
    personalAdvInjury: z.number(),
  }),
  autoLiability: z.object({
    combinedSingleLimit: z.number(),
  }),
  umbrellaExcess: z.object({
    eachOccurrence: z.number(),
    aggregate: z.number(),
  }).nullable(),
  workersComp: z.object({
    eachAccident: z.number(),
    diseasePolicyLimit: z.number(),
    diseaseEachEmployee: z.number(),
  }),
  additionalInsured: z.boolean(),
  waiverOfSubrogation: z.boolean(),
  certificateHolder: z.string(),
});

export type COIDataType = z.infer<typeof COIData>;

export interface COIRequirements {
  minGeneralLiability: number;
  minAutoLiability: number;
  minUmbrella: number;
  minWorkersComp: number;
  requireAdditionalInsured: boolean;
  requireWaiverOfSubrogation: boolean;
}

export interface COIValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
  expiresInDays: number;
}

const DEFAULT_REQUIREMENTS: COIRequirements = {
  minGeneralLiability: 1_000_000,
  minAutoLiability: 1_000_000,
  minUmbrella: 2_000_000,
  minWorkersComp: 500_000,
  requireAdditionalInsured: true,
  requireWaiverOfSubrogation: true,
};

export class COIValidator {
  validate(coi: COIDataType, requirements: COIRequirements = DEFAULT_REQUIREMENTS): COIValidationResult {
    const errors: string[] = [];
    const warnings: string[] = [];

    // Expiration check
    const expiry = new Date(coi.expirationDate);
    const now = new Date();
    const expiresInDays = Math.floor((expiry.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));

    if (expiresInDays < 0) {
      errors.push(`COI expired ${Math.abs(expiresInDays)} days ago (${coi.expirationDate})`);
    } else if (expiresInDays < 30) {
      warnings.push(`COI expires in ${expiresInDays} days (${coi.expirationDate})`);
    }

    // General Liability minimums
    if (coi.generalLiability.eachOccurrence < requirements.minGeneralLiability) {
      errors.push(`GL each occurrence ($${coi.generalLiability.eachOccurrence.toLocaleString()}) below minimum ($${requirements.minGeneralLiability.toLocaleString()})`);
    }

    // Auto Liability
    if (coi.autoLiability.combinedSingleLimit < requirements.minAutoLiability) {
      errors.push(`Auto liability ($${coi.autoLiability.combinedSingleLimit.toLocaleString()}) below minimum ($${requirements.minAutoLiability.toLocaleString()})`);
    }

    // Umbrella/Excess
    if (requirements.minUmbrella > 0) {
      if (!coi.umbrellaExcess) {
        errors.push('Umbrella/excess coverage required but not present');
      } else if (coi.umbrellaExcess.eachOccurrence < requirements.minUmbrella) {
        errors.push(`Umbrella ($${coi.umbrellaExcess.eachOccurrence.toLocaleString()}) below minimum ($${requirements.minUmbrella.toLocaleString()})`);
      }
    }

    // Workers Comp
    if (coi.workersComp.eachAccident < requirements.minWorkersComp) {
      errors.push(`Workers comp ($${coi.workersComp.eachAccident.toLocaleString()}) below minimum ($${requirements.minWorkersComp.toLocaleString()})`);
    }

    // Additional insured
    if (requirements.requireAdditionalInsured && !coi.additionalInsured) {
      errors.push('Additional insured endorsement required but not indicated');
    }

    // Waiver of subrogation
    if (requirements.requireWaiverOfSubrogation && !coi.waiverOfSubrogation) {
      errors.push('Waiver of subrogation required but not indicated');
    }

    return { valid: errors.length === 0, errors, warnings, expiresInDays };
  }
}
