package com.forge.platform.identity;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface IdentityMapper {

    @Mapping(target = "role", expression = "java(user.getRole().name())")
    AuthDtos.UserDto toUserDto(User user);

    @Mapping(target = "maskedWebhookSecret", source = "maskedSecret")
    @Mapping(target = "maskedGithubToken", source = "maskedGithubToken")
    @Mapping(target = "githubTokenConfigured", source = "githubTokenConfigured")
    AuthDtos.TenantDto toTenantDto(Tenant tenant, String maskedSecret, String maskedGithubToken, boolean githubTokenConfigured);
}
