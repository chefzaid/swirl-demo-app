import { beforeEach, describe, expect, it, type MockedObject, vi } from "vitest";
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LoginComponent } from './login.component';
import { AuthService } from '../../services/auth.service';
import { CommonModule } from '@angular/common';
import { of } from 'rxjs';

describe('LoginComponent', () => {
    let component: LoginComponent;
    let fixture: ComponentFixture<LoginComponent>;
    let authServiceSpy: MockedObject<AuthService>;

    beforeEach(async () => {
        const authSpy = {
            login: vi.fn().mockName("AuthService.login"),
            authStatus$: of('ready' as const)
        };

        await TestBed.configureTestingModule({
            imports: [LoginComponent, CommonModule],
            providers: [
                { provide: AuthService, useValue: authSpy }
            ]
        }).compileComponents();

        authServiceSpy = TestBed.inject(AuthService) as MockedObject<AuthService>;
        fixture = TestBed.createComponent(LoginComponent);
        component = fixture.componentInstance;
        fixture.detectChanges();
    });

    it('should create', () => {
        expect(component).toBeTruthy();
    });

    it('says this is the reference flow of a service template and links its README', () => {
        const note = (fixture.nativeElement as HTMLElement).querySelector('.story-lead') as HTMLElement;
        expect(note.textContent?.replace(/\s+/g, ' ')).toContain('This is the reference flow of a service template: read what it is for.');
        expect(note.querySelector('a')?.getAttribute('href')).toBe('https://github.com/chefzaid/swirl-demo-app#readme');
    });

    it('should call authService.login on login', () => {
        component.login();
        expect(authServiceSpy.login).toHaveBeenCalled();
    });
});
