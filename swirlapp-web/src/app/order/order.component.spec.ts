import { beforeEach, describe, expect, it, type MockedObject, vi } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { OrderComponent } from './order.component';
import { OrderService } from '../services/order.service';
import { UserService } from '../services/user.service';
import { Order } from '../models/order.model';
import { User } from '../models/user.model';

describe('OrderComponent', () => {
  let component: OrderComponent;
  let fixture: ComponentFixture<OrderComponent>;
  let orderService: MockedObject<OrderService>;
  let userService: MockedObject<UserService>;

  const alice: User = {
    id: 1,
    name: 'Alice Example',
    username: 'alice',
    email: 'alice@example.com'
  };
  const order: Order = {
    id: 1,
    userId: 1,
    userName: 'Alice Example',
    productId: 10,
    status: 'PENDING'
  };

  beforeEach(() => {
    orderService = {
      getAllOrders: vi.fn().mockReturnValue(of([])),
      createOrder: vi.fn(),
      updateOrder: vi.fn(),
      deleteOrder: vi.fn()
    } as unknown as MockedObject<OrderService>;
    userService = {
      getAllUsers: vi.fn().mockReturnValue(of([]))
    } as unknown as MockedObject<UserService>;

    TestBed.configureTestingModule({
      imports: [OrderComponent],
      providers: [
        { provide: OrderService, useValue: orderService },
        { provide: UserService, useValue: userService }
      ]
    });
    fixture = TestBed.createComponent(OrderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('explains each order status instead of a fixed pipeline line', () => {
    orderService.getAllOrders.mockReturnValue(of([
      order,
      { ...order, id: 2, status: 'APPROVED' },
      { ...order, id: 3, status: 'REJECTED' }
    ]));

    component.loadOrders();
    fixture.detectChanges();

    const element = fixture.nativeElement as HTMLElement;
    const notes = Array.from(element.querySelectorAll('.status-note'), note => note.textContent?.trim());
    expect(notes).toEqual([
      'Waiting for the user service to check that the owner exists.',
      'Approved: the user service found the owner.',
      'Rejected: the user service found no user with this ID.'
    ]);
    expect(component.statusNotes.COMPLETED).toBe('Completed: a reserved status; no step of this example moves an order here.');
    expect(element.textContent).not.toContain('Event pipeline connected');
    expect(element.textContent).not.toContain('warms Redis');
  });

  it('creates and loads orders', () => {
    orderService.getAllOrders.mockReturnValue(of([order]));

    component.loadOrders();

    expect(component.orders()).toEqual([order]);
    expect(component.loading()).toBe(false);
    expect(component.error()).toBeNull();
  });

  it('reports an order load failure', () => {
    orderService.getAllOrders.mockReturnValue(throwError(() => 'order load failed'));

    component.loadOrders();

    expect(component.error()).toBe('order load failed');
    expect(component.loading()).toBe(false);
  });

  it('loads users', () => {
    userService.getAllUsers.mockReturnValue(of([alice]));

    component.loadUsers();

    expect(component.users()).toEqual([alice]);
    expect(component.loadingUsers()).toBe(false);
  });

  it('finishes loading users after a failure', () => {
    userService.getAllUsers.mockReturnValue(throwError(() => 'user load failed'));

    component.loadUsers();

    expect(component.loadingUsers()).toBe(false);
  });

  it('validates positive IDs before creating an order', () => {
    component.newOrder = { userId: 0, productId: 0 };

    component.createOrder();

    expect(component.error()).toContain('positive product ID');
    expect(orderService.createOrder).not.toHaveBeenCalled();
  });

  it('creates an order and resets the form', () => {
    component.newOrder = { userId: 1, productId: 10 };
    orderService.createOrder.mockReturnValue(of(order));

    component.createOrder();

    expect(component.orders()).toEqual([order]);
    expect(component.newOrder).toEqual({ userId: 0, productId: 0 });
    expect(component.creating()).toBe(false);
  });

  it('reports an order create failure', () => {
    component.newOrder = { userId: 1, productId: 10 };
    orderService.createOrder.mockReturnValue(throwError(() => 'create order failed'));

    component.createOrder();

    expect(component.error()).toBe('create order failed');
    expect(component.creating()).toBe(false);
  });

  it('updates an order and closes the edit form', () => {
    const updated = { ...order, productId: 20, status: 'PENDING' as const, userName: null };
    component.orders.set([order]);
    component.startEditing(order);
    component.editOrder.productId = 20;
    orderService.updateOrder.mockReturnValue(of(updated));

    component.updateOrder();

    expect(orderService.updateOrder).toHaveBeenCalledWith(1, { userId: 1, productId: 20 });
    expect(component.orders()).toEqual([updated]);
    expect(component.editingOrderId()).toBeNull();
    expect(component.saving()).toBe(false);
  });

  it('reports an order update failure', () => {
    component.startEditing(order);
    orderService.updateOrder.mockReturnValue(throwError(() => 'update order failed'));

    component.updateOrder();

    expect(component.error()).toBe('update order failed');
    expect(component.saving()).toBe(false);
  });

  it('deletes an order after confirmation', () => {
    component.orders.set([order]);
    component.requestDelete(order.id);
    orderService.deleteOrder.mockReturnValue(of(undefined));

    component.deleteOrder(order);

    expect(orderService.deleteOrder).toHaveBeenCalledWith(1);
    expect(component.orders()).toEqual([]);
    expect(component.pendingDeleteOrderId()).toBeNull();
    expect(component.deletingOrderId()).toBeNull();
  });
});
