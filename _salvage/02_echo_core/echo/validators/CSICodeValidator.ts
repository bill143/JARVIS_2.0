/**
 * MODULE 6 — CONSTRUCTION VALIDATOR: CSI MasterFormat Codes
 * Validates 6-digit MasterFormat codes against master list
 */

// CSI MasterFormat 2024 Division structure
const CSI_DIVISIONS: Record<string, string> = {
  '00': 'Procurement and Contracting Requirements',
  '01': 'General Requirements',
  '02': 'Existing Conditions',
  '03': 'Concrete',
  '04': 'Masonry',
  '05': 'Metals',
  '06': 'Wood, Plastics, and Composites',
  '07': 'Thermal and Moisture Protection',
  '08': 'Openings',
  '09': 'Finishes',
  '10': 'Specialties',
  '11': 'Equipment',
  '12': 'Furnishings',
  '13': 'Special Construction',
  '14': 'Conveying Equipment',
  '21': 'Fire Suppression',
  '22': 'Plumbing',
  '23': 'Heating, Ventilating, and Air Conditioning (HVAC)',
  '25': 'Integrated Automation',
  '26': 'Electrical',
  '27': 'Communications',
  '28': 'Electronic Safety and Security',
  '31': 'Earthwork',
  '32': 'Exterior Improvements',
  '33': 'Utilities',
  '34': 'Transportation',
  '35': 'Waterway and Marine Construction',
  '40': 'Process Interconnections',
  '41': 'Material Processing and Handling Equipment',
  '42': 'Process Heating, Cooling, and Drying Equipment',
  '43': 'Process Gas and Liquid Handling, Purification, and Storage Equipment',
  '44': 'Pollution and Waste Control Equipment',
  '45': 'Industry-Specific Manufacturing Equipment',
  '46': 'Water and Wastewater Equipment',
  '48': 'Electrical Power Generation',
};

export interface CSIValidationResult {
  valid: boolean;
  code: string;
  division: string | null;
  divisionName: string | null;
  errors: string[];
}

export class CSICodeValidator {
  /** Validate a single CSI MasterFormat code */
  validate(code: string): CSIValidationResult {
    const errors: string[] = [];
    const cleaned = code.replace(/[\s.-]/g, '');

    // Must be 6 digits
    if (!/^\d{6}$/.test(cleaned)) {
      errors.push(`CSI code must be 6 digits, got "${code}" (${cleaned.length} digits after cleanup)`);
      return { valid: false, code, division: null, divisionName: null, errors };
    }

    // First 2 digits = Division
    const division = cleaned.substring(0, 2);
    const divisionName = CSI_DIVISIONS[division] ?? null;

    if (!divisionName) {
      errors.push(`Unknown CSI division: ${division}. Valid divisions: ${Object.keys(CSI_DIVISIONS).join(', ')}`);
    }

    return {
      valid: errors.length === 0,
      code: `${cleaned.substring(0, 2)} ${cleaned.substring(2, 4)} ${cleaned.substring(4, 6)}`,
      division,
      divisionName,
      errors,
    };
  }

  /** Validate an array of CSI codes */
  validateBatch(codes: string[]): {
    results: CSIValidationResult[];
    allValid: boolean;
    invalidCount: number;
  } {
    const results = codes.map(c => this.validate(c));
    const invalidCount = results.filter(r => !r.valid).length;
    return { results, allValid: invalidCount === 0, invalidCount };
  }

  /** Get division name for a code */
  getDivision(code: string): string | null {
    const division = code.replace(/[\s.-]/g, '').substring(0, 2);
    return CSI_DIVISIONS[division] ?? null;
  }

  /** List all valid divisions */
  listDivisions(): Array<{ code: string; name: string }> {
    return Object.entries(CSI_DIVISIONS).map(([code, name]) => ({ code, name }));
  }
}
