import { createContext } from 'react';

/**
 * The column count of the enclosing wireframe-kit table (Tbl of shared/ui/wireframe) when a list
 * renders inside its body: a paginated list then draws its loading, empty and pager states as rows
 * spanning the columns, never as a paragraph or a block inside tbody (invalid HTML that the server
 * render and the browser would hydrate differently). null outside a table.
 */
export const TableColumns = createContext<number | null>(null);
