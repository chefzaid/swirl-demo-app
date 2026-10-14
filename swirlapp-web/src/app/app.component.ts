import { Component, ChangeDetectionStrategy, inject } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { AsyncPipe } from '@angular/common';
import { NotificationComponent } from './components/notification/notification.component';
import { AuthService } from './services/auth.service';
import { RUNTIME_CONFIG } from './runtime-config';
import { Observable } from 'rxjs';

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, AsyncPipe, NotificationComponent]
})
export class AppComponent {
  title = 'Swirl Demo App';
  isLoggedIn$: Observable<boolean>;
  readonly apiDocsEnabled = inject(RUNTIME_CONFIG)?.apiDocsEnabled ?? true;

  constructor(public authService: AuthService) {
    this.isLoggedIn$ = this.authService.isLoggedIn$;
  }

  logout(): void {
    this.authService.logout();
  }
}
