package com.zps.zest.rag.models;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/**
 * Model for OpenWebUI Knowledge Collection response.
 * Matches the structure expected by OpenWebUI API.
 */
public class KnowledgeCollection {
    private String id;
    
    @SerializedName("user_id")
    private String userId;
    
    private String name;
    private String description;
    private KnowledgeData data;
    private Map<String, Object> meta;
    
    @SerializedName("access_control")
    private Map<String, Object> accessControl;
    
    @SerializedName("created_at")
    private Long createdAt;
    
    @SerializedName("updated_at")
    private Long updatedAt;
    
    private List<FileMetadata> files;
    private String type = "collection";
    private String status = "processed";
    
    // User info (optional)
    private UserInfo user;
    
    // Getters and setters
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public KnowledgeData getData() {
        return data;
    }
    
    public void setData(KnowledgeData data) {
        this.data = data;
    }
    
    public Map<String, Object> getMeta() {
        return meta;
    }
    
    public void setMeta(Map<String, Object> meta) {
        this.meta = meta;
    }
    
    public Map<String, Object> getAccessControl() {
        return accessControl;
    }
    
    public void setAccessControl(Map<String, Object> accessControl) {
        this.accessControl = accessControl;
    }
    
    public Long getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
    
    public Long getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public List<FileMetadata> getFiles() {
        return files;
    }
    
    public void setFiles(List<FileMetadata> files) {
        this.files = files;
    }
    
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public UserInfo getUser() {
        return user;
    }
    
    public void setUser(UserInfo user) {
        this.user = user;
    }
    
    /**
     * Inner class for knowledge data.
     */
    public static class KnowledgeData {
        @SerializedName("file_ids")
        private List<String> fileIds;
        
        public List<String> getFileIds() {
            return fileIds;
        }
        
        public void setFileIds(List<String> fileIds) {
            this.fileIds = fileIds;
        }
    }
    
    /**
     * Inner class for file metadata.
     */
    public static class FileMetadata {
        private String id;
        private FileMetaInfo meta;
        
        @SerializedName("created_at")
        private Long createdAt;
        
        @SerializedName("updated_at")
        private Long updatedAt;
        
        public String getId() {
            return id;
        }
        
        public void setId(String id) {
            this.id = id;
        }
        
        public FileMetaInfo getMeta() {
            return meta;
        }
        
        public void setMeta(FileMetaInfo meta) {
            this.meta = meta;
        }
        
        public Long getCreatedAt() {
            return createdAt;
        }
        
        public void setCreatedAt(Long createdAt) {
            this.createdAt = createdAt;
        }
        
        public Long getUpdatedAt() {
            return updatedAt;
        }
        
        public void setUpdatedAt(Long updatedAt) {
            this.updatedAt = updatedAt;
        }
    }
    
    /**
     * Inner class for file meta information.
     */
    public static class FileMetaInfo {
        private String name;
        
        @SerializedName("content_type")
        private String contentType;
        
        private Integer size;
        private Map<String, Object> data;
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getContentType() {
            return contentType;
        }
        
        public void setContentType(String contentType) {
            this.contentType = contentType;
        }
        
        public Integer getSize() {
            return size;
        }
        
        public void setSize(Integer size) {
            this.size = size;
        }
        
        public Map<String, Object> getData() {
            return data;
        }
        
        public void setData(Map<String, Object> data) {
            this.data = data;
        }
    }
    
    /**
     * Inner class for user information.
     */
    public static class UserInfo {
        private String id;
        private String name;
        private String email;
        private String role;
        
        @SerializedName("profile_image_url")
        private String profileImageUrl;
        
        public String getId() {
            return id;
        }
        
        public void setId(String id) {
            this.id = id;
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getEmail() {
            return email;
        }
        
        public void setEmail(String email) {
            this.email = email;
        }
        
        public String getRole() {
            return role;
        }
        
        public void setRole(String role) {
            this.role = role;
        }
        
        public String getProfileImageUrl() {
            return profileImageUrl;
        }
        
        public void setProfileImageUrl(String profileImageUrl) {
            this.profileImageUrl = profileImageUrl;
        }
    }
}
