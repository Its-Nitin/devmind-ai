import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { correlationIdInterceptor } from './correlation-id-interceptor';

describe('correlationIdInterceptor', () => {
  it('gives every request its own UUID in X-Correlation-Id', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([correlationIdInterceptor])), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpClient);
    const backend = TestBed.inject(HttpTestingController);

    http.get('/api/projects').subscribe();
    http.get('/api/projects').subscribe();
    const [first, second] = backend.match('/api/projects').map((request) => request.request.headers);

    const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
    expect(first?.get('X-Correlation-Id')).toMatch(uuid);
    expect(second?.get('X-Correlation-Id')).toMatch(uuid);
    expect(first?.get('X-Correlation-Id')).not.toBe(second?.get('X-Correlation-Id'));
  });
});
