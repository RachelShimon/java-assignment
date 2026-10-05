import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators
} from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import {
  ApiError,
  Employee,
  LeaveRequest,
  LeaveStatus,
  LeaveType
} from '../models/leave-request.model';
import { EmployeeService } from '../services/employee.service';
import { LeaveRequestService } from '../services/leave-request.service';

// Cross-field rule: the range must not be negative (end before start).
function dateRangeValidator(group: AbstractControl): ValidationErrors | null {
  const start = group.get('startDate')?.value;
  const end = group.get('endDate')?.value;
  return start && end && end < start ? { dateRange: true } : null;
}

@Component({
  selector: 'app-leave-requests',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './leave-requests.component.html',
  styleUrls: ['./leave-requests.component.css']
})
export class LeaveRequestsComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(NonNullableFormBuilder);

  requests: LeaveRequest[] = [];
  employees: Employee[] = [];
  loading = false;
  loadError = '';

  form = this.fb.group(
    {
      employeeId: this.fb.control<number | null>(null, Validators.required),
      type: this.fb.control<LeaveType | null>(null, Validators.required),
      startDate: this.fb.control('', Validators.required),
      endDate: this.fb.control('', Validators.required)
    },
    { validators: dateRangeValidator }
  );
  submitting = false;
  submitError = '';
  submitSuccess = '';

  approvingIds = new Set<number>();
  approveError = '';
  approveSuccess = '';

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

  readonly leaveTypeOptions = Object.entries(this.typeLabels)
    .map(([value, label]) => ({ value: Number(value) as LeaveType, label }));

  constructor(
    private leaveRequestService: LeaveRequestService,
    private employeeService: EmployeeService
  ) {}

  ngOnInit(): void {
    this.load();
    this.employeeService.getAll()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((employees) => (this.employees = employees));
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

  // Days the current form selection covers (inclusive); negative ranges are blocked by validation.
  get requestedDays(): number | null {
    const { startDate, endDate } = this.form.getRawValue();
    if (!startDate || !endDate || endDate < startDate) return null;
    const ms = new Date(endDate).getTime() - new Date(startDate).getTime();
    return Math.round(ms / 86_400_000) + 1;
  }

  onSubmit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting) return;

    const { employeeId, type, startDate, endDate } = this.form.getRawValue();
    this.submitting = true;
    this.submitError = '';
    this.submitSuccess = '';

    this.leaveRequestService.create({ employeeId: employeeId!, type: type!, startDate, endDate })
      .pipe(takeUntilDestroyed(this.destroyRef), finalize(() => (this.submitting = false)))
      .subscribe({
        next: (created) => {
          // The create response carries no employee object; resolve it locally.
          created.employee = this.employees.find((e) => e.id === created.employeeId);
          this.requests = [...this.requests, created]
            .sort((a, b) => b.startDate.localeCompare(a.startDate));
          this.submitSuccess = `Request #${created.id} submitted (${created.days} days).`;
          this.form.reset();
        },
        error: (err: HttpErrorResponse) => {
          this.submitError = (err.error as ApiError)?.message ?? 'Failed to submit the request.';
        }
      });
  }

  approve(request: LeaveRequest): void {
    if (this.approvingIds.has(request.id)) return;

    this.approvingIds.add(request.id);
    this.approveError = '';
    this.approveSuccess = '';

    this.leaveRequestService.approve(request.id)
      .pipe(takeUntilDestroyed(this.destroyRef), finalize(() => this.approvingIds.delete(request.id)))
      .subscribe({
        next: (updated) => {
          // Update only the affected row; no full reload.
          this.requests = this.requests.map((r) =>
            r.id === updated.id ? { ...updated, employee: updated.employee ?? r.employee } : r
          );
          this.approveSuccess = `Request #${updated.id} approved.`;
        },
        error: (err: HttpErrorResponse) => {
          this.approveError = (err.error as ApiError)?.message ?? 'Approval failed.';
          // On a state conflict (e.g. approved elsewhere) resync the list.
          if (err.status === 409) this.load();
        }
      });
  }

  isApproving(id: number): boolean {
    return this.approvingIds.has(id);
  }

  typeLabel(type: LeaveType): string {
    return this.typeLabels[type] ?? 'Unknown';
  }

  statusLabel(status: LeaveStatus): string {
    return this.statusLabels[status] ?? 'Unknown';
  }
}
