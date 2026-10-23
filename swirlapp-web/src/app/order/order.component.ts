import { ChangeDetectionStrategy, Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OrderService } from '../services/order.service';
import { UserService } from '../services/user.service';
import { CreateOrderRequest, Order, OrderStatus } from '../models/order.model';
import { User } from '../models/user.model';

@Component({
  selector: 'app-order',
  templateUrl: './order.component.html',
  styleUrls: ['./order.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule]
})
export class OrderComponent implements OnInit {
  readonly orders = signal<Order[]>([]);
  readonly users = signal<User[]>([]);
  newOrder: CreateOrderRequest = this.initOrder();
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly creating = signal(false);
  readonly loadingUsers = signal(false);
  readonly editingOrderId = signal<number | null>(null);
  editOrder: CreateOrderRequest = this.initOrder();
  readonly saving = signal(false);
  readonly pendingDeleteOrderId = signal<number | null>(null);
  readonly deletingOrderId = signal<number | null>(null);
  readonly statusNotes: Record<OrderStatus, string> = {
    PENDING: 'Waiting for the user service to check that the owner exists.',
    APPROVED: 'Approved: the user service found the owner.',
    REJECTED: 'Rejected: the user service found no user with this ID.',
    COMPLETED: 'Completed: a reserved status; no step of this example moves an order here.'
  };

  constructor(
    private readonly orderService: OrderService,
    private readonly userService: UserService
  ) { }

  ngOnInit(): void {
    this.loadOrders();
    this.loadUsers();
  }

  loadOrders(): void {
    this.loading.set(true);
    this.error.set(null);
    this.orderService.getAllOrders().subscribe({
      next: (data) => {
        this.orders.set(data);
        this.loading.set(false);
      },
      error: (error) => {
        this.error.set(String(error));
        this.loading.set(false);
        console.error('Error loading orders:', error);
      }
    });
  }

  loadUsers(): void {
    this.loadingUsers.set(true);
    this.userService.getAllUsers().subscribe({
      next: (data) => {
        this.users.set(data);
        this.loadingUsers.set(false);
      },
      error: (error) => {
        console.error('Error loading users:', error);
        this.loadingUsers.set(false);
      }
    });
  }

  createOrder(): void {
    if (this.newOrder.userId <= 0 || this.newOrder.productId <= 0) {
      this.error.set('Please select a user and enter a positive product ID');
      return;
    }

    this.creating.set(true);
    this.error.set(null);
    this.orderService.createOrder(this.newOrder).subscribe({
      next: (order) => {
        this.orders.update(orders => [...orders, order]);
        this.newOrder = this.initOrder();
        this.creating.set(false);
      },
      error: (error) => {
        this.error.set(String(error));
        this.creating.set(false);
        console.error('Error creating order:', error);
      }
    });
  }

  startEditing(order: Order): void {
    this.editingOrderId.set(order.id);
    this.editOrder = { userId: order.userId, productId: order.productId };
    this.pendingDeleteOrderId.set(null);
    this.error.set(null);
  }

  cancelEditing(): void {
    this.editingOrderId.set(null);
    this.editOrder = this.initOrder();
  }

  updateOrder(): void {
    const orderId = this.editingOrderId();
    if (orderId === null || this.editOrder.userId <= 0 || this.editOrder.productId <= 0) {
      this.error.set('Please select a user and enter a positive product ID');
      return;
    }

    this.saving.set(true);
    this.error.set(null);
    this.orderService.updateOrder(orderId, this.editOrder).subscribe({
      next: (updated) => {
        this.orders.update(orders => orders.map(order => order.id === updated.id ? updated : order));
        this.saving.set(false);
        this.cancelEditing();
      },
      error: (error) => {
        this.error.set(String(error));
        this.saving.set(false);
      }
    });
  }

  requestDelete(orderId: number): void {
    this.pendingDeleteOrderId.set(orderId);
    this.error.set(null);
  }

  cancelDelete(): void {
    this.pendingDeleteOrderId.set(null);
  }

  deleteOrder(order: Order): void {
    this.deletingOrderId.set(order.id);
    this.error.set(null);
    this.orderService.deleteOrder(order.id).subscribe({
      next: () => {
        this.orders.update(orders => orders.filter(candidate => candidate.id !== order.id));
        if (this.editingOrderId() === order.id) {
          this.cancelEditing();
        }
        this.pendingDeleteOrderId.set(null);
        this.deletingOrderId.set(null);
      },
      error: (error) => {
        this.error.set(String(error));
        this.deletingOrderId.set(null);
      }
    });
  }

  initOrder(): CreateOrderRequest {
    return { userId: 0, productId: 0 };
  }
}
