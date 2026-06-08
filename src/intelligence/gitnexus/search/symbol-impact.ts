/**
 * Symbol impact — blast-radius estimation from graph presence.
 *
 * Until call-edge extraction lands (Phase 3d), impact is approximated
 * by counting symbols in the same file and checking export status.
 * Exported symbols = higher risk because they are reachable from
 * outside the file.
 */

import type { GraphStore } from '../graph/graph-store';
import { findSymbolDefinition, findNeighboursInFile } from './symbol-context';

export interface SymbolImpact {
  /** The symbol that was queried. */
  definition: {
    id: string;
    name: string;
    filePath: string;
    isExported: boolean;
  } | null;
  /** Number of other symbols in the same file. */
  neighbourCount: number;
  /** Whether the symbol is exported (higher risk). */
  isExported: boolean;
  /** Risk heuristic: low (<3 neighbours, not exported), medium (exported or 3-10 neighbours), high (>10 neighbours and exported). */
  riskLevel: 'low' | 'medium' | 'high';
}

export const getSymbolImpact = async (
  store: GraphStore,
  symbolName: string,
): Promise<SymbolImpact> => {
  const definition = await findSymbolDefinition(store, symbolName);
  if (!definition) {
    return {
      definition: null,
      neighbourCount: 0,
      isExported: false,
      riskLevel: 'low',
    };
  }

  const neighbours = await findNeighboursInFile(store, definition.filePath);
  const neighbourCount = neighbours.length - 1; // exclude self
  const isExported = definition.isExported;

  let riskLevel: SymbolImpact['riskLevel'] = 'low';
  if (isExported && neighbourCount > 10) {
    riskLevel = 'high';
  } else if (isExported || neighbourCount >= 3) {
    riskLevel = 'medium';
  }

  return {
    definition: {
      id: definition.id,
      name: definition.name,
      filePath: definition.filePath,
      isExported,
    },
    neighbourCount,
    isExported,
    riskLevel,
  };
};
