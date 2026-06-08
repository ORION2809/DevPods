/**
 * Tree-sitter query strings for symbol extraction.
 *
 * Each query uses dual captures:
 *   @name — the identifier node (for the symbol name)
 *   @definition.* — the outer definition node (for line range)
 *
 * Harvested from GitNexus donor and simplified for Phase 2d.
 */

// --------------------------------------------------------------------------
// TypeScript / TSX
// --------------------------------------------------------------------------

export const TYPESCRIPT_QUERIES = `
; Classes
(class_declaration
  name: (type_identifier) @name) @definition.class

(abstract_class_declaration
  name: (type_identifier) @name) @definition.class

; Interfaces
(interface_declaration
  name: (type_identifier) @name) @definition.interface

; Functions
(function_declaration
  name: (identifier) @name) @definition.function

; Methods
(method_definition
  name: (property_identifier) @name) @definition.method

(method_definition
  name: (private_property_identifier) @name) @definition.method

(abstract_method_signature
  name: (property_identifier) @name) @definition.method

(method_signature
  name: (property_identifier) @name) @definition.method

; Arrow functions in const/let
(lexical_declaration
  (variable_declarator
    name: (identifier) @name
    value: (arrow_function))) @definition.function

(lexical_declaration
  (variable_declarator
    name: (identifier) @name
    value: (function_expression))) @definition.function

; Object-property functions
(pair
  key: (property_identifier) @name
  value: (arrow_function)) @definition.function

; Properties / fields
(public_field_definition
  name: (property_identifier) @name) @definition.property

(public_field_definition
  name: (private_property_identifier) @name) @definition.property

; Variables / constants (non-function)
(lexical_declaration
  (variable_declarator
    name: (identifier) @name)) @definition.const
`;

// --------------------------------------------------------------------------
// JavaScript / JSX
// --------------------------------------------------------------------------

export const JAVASCRIPT_QUERIES = `
; Classes
(class_declaration
  name: (identifier) @name) @definition.class

; Functions
(function_declaration
  name: (identifier) @name) @definition.function

; Methods
(method_definition
  name: (property_identifier) @name) @definition.method

(method_definition
  name: (private_property_identifier) @name) @definition.method

; Arrow functions in const/let
(lexical_declaration
  (variable_declarator
    name: (identifier) @name
    value: (arrow_function))) @definition.function

(lexical_declaration
  (variable_declarator
    name: (identifier) @name
    value: (function_expression))) @definition.function

; Object-property functions
(pair
  key: (property_identifier) @name
  value: (arrow_function)) @definition.function

; Variables / constants
(lexical_declaration
  (variable_declarator
    name: (identifier) @name)) @definition.const

(variable_declaration
  (variable_declarator
    name: (identifier) @name)) @definition.variable
`;

// --------------------------------------------------------------------------
// Kotlin
// --------------------------------------------------------------------------

export const KOTLIN_QUERIES = `
; Classes
(class_declaration
  "class"
  (type_identifier) @name) @definition.class

; Interfaces
(class_declaration
  "interface"
  (type_identifier) @name) @definition.interface

; Functions
(function_declaration
  (simple_identifier) @name) @definition.function

; Methods (functions inside classes)
(class_body
  (function_declaration
    (simple_identifier) @name) @definition.method)

; Properties
(property_declaration
  (variable_declaration
    (simple_identifier) @name) @definition.property)
`;
