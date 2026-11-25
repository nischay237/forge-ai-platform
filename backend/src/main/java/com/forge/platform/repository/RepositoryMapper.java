package com.forge.platform.repository;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface RepositoryMapper {
    RepositoryDtos.RepositoryDto toDto(RepositoryEntity entity);
    RepositoryDtos.SourceFileSummaryDto toSummaryDto(SourceFileEntity entity);
    RepositoryDtos.SourceFileDetailDto toDetailDto(SourceFileEntity entity);
}
