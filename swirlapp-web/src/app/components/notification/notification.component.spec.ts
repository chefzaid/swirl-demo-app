import { beforeEach, describe, expect, it } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NotificationComponent } from './notification.component';
import { NotificationService } from '../../services/notification.service';

describe('NotificationComponent', () => {
  let fixture: ComponentFixture<NotificationComponent>;
  let notifications: NotificationService;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [NotificationComponent] });
    notifications = TestBed.inject(NotificationService);
    fixture = TestBed.createComponent(NotificationComponent);
  });

  function shown(): HTMLElement[] {
    fixture.detectChanges();
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.notification'));
  }

  it('shows each notification with its type styling and icon', () => {
    notifications.show('success', 'Saved', 0);
    notifications.show('error', 'Failed', 0);
    notifications.show('warning', 'Careful', 0);
    notifications.show('info', 'Heads up', 0);

    expect(shown().map(item => [item.className, item.querySelector('.notification-icon')?.textContent,
      item.querySelector('.notification-message')?.textContent])).toEqual([
      ['notification notification-success', '✅', 'Saved'],
      ['notification notification-error', '❌', 'Failed'],
      ['notification notification-warning', '⚠️', 'Careful'],
      ['notification notification-info', 'ℹ️', 'Heads up']
    ]);
  });

  it('falls back to the info icon for an unknown type', () => {
    expect(fixture.componentInstance.getIconForType('unknown')).toBe('ℹ️');
  });

  it('removes a notification when its close button is pressed', () => {
    notifications.show('info', 'Dismiss me', 0);
    shown()[0].querySelector<HTMLButtonElement>('.notification-close')?.click();

    expect(shown()).toEqual([]);
  });
});
