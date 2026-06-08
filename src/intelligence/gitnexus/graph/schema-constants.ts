/**
 * CodeGraph schema constants — harvested from GitNexus donor, terminology adapted.
 *
 * NODE_TYPES and EDGE_TYPES define what the knowledge graph can contain.
 * These must remain stable across index versions for data compatibility.
 */

export const NODE_TYPES = [
  'File',
  'Folder',
  'Function',
  'Class',
  'Interface',
  'Method',
  'CodeElement',
  'Community',
  'Process',
  'Section',
  'Struct',
  'Enum',
  'Macro',
  'Typedef',
  'Union',
  'Namespace',
  'Trait',
  'Impl',
  'TypeAlias',
  'Const',
  'Static',
  'Variable',
  'Property',
  'Record',
  'Delegate',
  'Annotation',
  'Constructor',
  'Template',
  'Module',
  'Route',
  'Tool',
] as const;

export type NodeTypeName = (typeof NODE_TYPES)[number];

export const EDGE_TABLE_NAME = 'CodeRelation';

export const EDGE_TYPES = [
  'CONTAINS',
  'DEFINES',
  'IMPORTS',
  'CALLS',
  'EXTENDS',
  'IMPLEMENTS',
  'HAS_METHOD',
  'HAS_PROPERTY',
  'ACCESSES',
  'METHOD_OVERRIDES',
  'OVERRIDES',
  'METHOD_IMPLEMENTS',
  'MEMBER_OF',
  'STEP_IN_PROCESS',
  'HANDLES_ROUTE',
  'FETCHES',
  'HANDLES_TOOL',
  'ENTRY_POINT_OF',
  'WRAPS',
  'QUERIES',
] as const;

export type EdgeType = (typeof EDGE_TYPES)[number];

export const EMBEDDING_TABLE_NAME = 'CodeEmbedding';
