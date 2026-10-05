import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { CreateLeaveRequest, LeaveRequest } from '../models/leave-request.model';

@Injectable({ providedIn: 'root' })
export class LeaveRequestService {
  private readonly apiUrl = 'http://localhost:5080/api/leave-requests';

  constructor(private http: HttpClient) {}

  getAll(): Observable<LeaveRequest[]> {
    return this.http.get<LeaveRequest[]>(this.apiUrl);
  }

  create(request: CreateLeaveRequest): Observable<LeaveRequest> {
    return this.http.post<LeaveRequest>(this.apiUrl, request);
  }

  approve(id: number): Observable<LeaveRequest> {
    return this.http.post<LeaveRequest>(`${this.apiUrl}/${id}/approve`, {});
  }
}
