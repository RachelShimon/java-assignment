import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Employee } from '../models/leave-request.model';

@Injectable({ providedIn: 'root' })
export class EmployeeService {
  private readonly apiUrl = 'http://localhost:5080/api/employees';

  constructor(private http: HttpClient) {}

  getAll(): Observable<Employee[]> {
    return this.http.get<Employee[]>(this.apiUrl);
  }
}
