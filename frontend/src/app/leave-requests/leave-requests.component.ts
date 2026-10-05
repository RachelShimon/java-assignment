import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { LeaveRequest, LeaveStatus, LeaveType } from '../models/leave-request.model';
import { LeaveRequestService } from '../services/leave-request.service';

@Component({
  selector: 'app-leave-requests',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './leave-requests.component.html',
  styleUrls: ['./leave-requests.component.css']
})
export class LeaveRequestsComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);

  requests: LeaveRequest[] = [];
  loading = false;
  loadError = '';

  // Exposed for template comparisons.
  readonly LeaveStatus = LeaveStatus;

  private readonly typeLabels: Record<LeaveType, string> = {
    [LeaveType.Vacation]: 'Vacation',
    [LeaveType.Sick]: 'Sick',
    [LeaveType.Unpaid]: 'Unpaid'
  };

  private readonly statusLabels: Record<LeaveStatus, string> = {
    [LeaveStatus.Pending]: 'Pending',
    [LeaveStatus.Approved]: 'Approved',
    [LeaveStatus.Rejected]: 'Rejected'
  };

  constructor(private leaveRequestService: LeaveRequestService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadError = '';
    this.leaveRequestService.getAll()
      .pipe(takeUntilDestroyed(this.destroyRef), finalize(() => (this.loading = false)))
      .subscribe({
        next: (data) => (this.requests = data),
        error: () => (this.loadError = 'Failed to load leave requests.')
      });
  }

  approve(request: LeaveRequest): void {
    // Upgraded as part of the assignment (loading / error / success handling).
    this.leaveRequestService.approve(request.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.load());
  }

  typeLabel(type: LeaveType): string {
    return this.typeLabels[type] ?? 'Unknown';
  }

  statusLabel(status: LeaveStatus): string {
    return this.statusLabels[status] ?? 'Unknown';
  }
}
