#if PROTOCOL_VERSION > 758 /* > 1.18.2 */
#include "botcraft/Game/Entities/entities/animal/frog/TadpoleEntity.hpp"

#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
#include <mutex>
#endif

namespace Botcraft
{
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
    const std::array<std::string, TadpoleEntity::metadata_count> TadpoleEntity::metadata_names{ {
        "age_locked",
    } };
#endif

    TadpoleEntity::TadpoleEntity()
    {
        // Initialize all metadata with default values
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        SetAgeLocked(false);
#endif

        // Initialize all attributes with default values
        attributes.insert({ EntityAttribute::Type::MovementSpeed, EntityAttribute(EntityAttribute::Type::MovementSpeed, 1.0) });
        attributes.insert({ EntityAttribute::Type::MaxHealth, EntityAttribute(EntityAttribute::Type::MaxHealth, 6.0) });
    }

    TadpoleEntity::~TadpoleEntity()
    {

    }


    std::string TadpoleEntity::GetName() const
    {
        return "tadpole";
    }

    EntityType TadpoleEntity::GetType() const
    {
        return EntityType::Tadpole;
    }


    std::string TadpoleEntity::GetClassName()
    {
        return "tadpole";
    }

    EntityType TadpoleEntity::GetClassType()
    {
        return EntityType::Tadpole;
    }


#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
    ProtocolCraft::Json::Value TadpoleEntity::Serialize() const
    {
        ProtocolCraft::Json::Value output = AbstractFishEntity::Serialize();

        output["metadata"]["age_locked"] = GetAgeLocked();

        return output;
    }


    void TadpoleEntity::SetMetadataValue(const int index, const std::any& value)
    {
        if (index < hierarchy_metadata_count)
        {
            AbstractFishEntity::SetMetadataValue(index, value);
        }
        else if (index - hierarchy_metadata_count < metadata_count)
        {
            std::scoped_lock<std::shared_mutex> lock(entity_mutex);
            metadata[metadata_names[index - hierarchy_metadata_count]] = value;
        }
    }


    bool TadpoleEntity::GetAgeLocked() const
    {
        std::shared_lock<std::shared_mutex> lock(entity_mutex);
        return std::any_cast<bool>(metadata.at("age_locked"));
    }


    void TadpoleEntity::SetAgeLocked(const bool age_locked)
    {
        std::scoped_lock<std::shared_mutex> lock(entity_mutex);
        metadata["age_locked"] = age_locked;
    }
#endif


    double TadpoleEntity::GetWidthImpl() const
    {
        return 0.4;
    }

    double TadpoleEntity::GetHeightImpl() const
    {
        return 0.3;
    }

}
#endif
