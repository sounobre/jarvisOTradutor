package com.dnobretech.jarvistradutorbackend.exception;

import java.time.Instant;
import java.util.List;

record ValidationError(int status, String error, List<FieldErr> errors, String path, Instant timestamp) {}