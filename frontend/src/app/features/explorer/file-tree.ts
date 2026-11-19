import { Component, input, output } from '@angular/core';
import { Tree } from 'primeng/tree';
import type { TreeNodeExpandEvent, TreeNodeSelectEvent } from 'primeng/types/tree';
import type { FileNode } from './explorer-store';

/**
 * Presentational file tree (FE-6): shows the nodes it's given, and asks the container to load a directory's children
 * on expand and to open a file on select. Keyboard-navigable through PrimeNG's tree (FE-11).
 */
@Component({
  selector: 'dm-file-tree',
  imports: [Tree],
  template: `
    <p-tree
      [value]="nodes()"
      selectionMode="single"
      emptyMessage="No files"
      ariaLabel="Files of the revision"
      (onNodeExpand)="onExpand($event)"
      (onNodeSelect)="onSelect($event)"
    />
  `,
})
export class FileTree {
  readonly nodes = input.required<FileNode[]>();
  readonly expand = output<FileNode>();
  readonly openFile = output<string>();

  protected onExpand(event: TreeNodeExpandEvent): void {
    this.expand.emit(event.node as FileNode);
  }

  protected onSelect(event: TreeNodeSelectEvent): void {
    const entry = (event.node as FileNode).data;
    if (entry?.type === 'FILE') {
      this.openFile.emit(entry.path);
    }
  }
}
