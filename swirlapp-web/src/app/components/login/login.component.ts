import { Component, ChangeDetectionStrategy } from '@angular/core';
import { AuthService } from '../../services/auth.service';
import { toSignal } from '@angular/core/rxjs-interop';

@Component({
  selector: 'app-login',
  templateUrl: './login.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./login.component.css']
})
export class LoginComponent {
  readonly readmeUrl = 'https://github.com/chefzaid/swirl-demo-app#readme';
  readonly authStatus = toSignal(this.authService.authStatus$, { initialValue: 'loading' });

  constructor(private readonly authService: AuthService) {}

  login() {
    this.authService.login();
  }
}
