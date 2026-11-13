import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/** The application shell. Features render into the router outlet. */
@Component({
  selector: 'dm-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {}
